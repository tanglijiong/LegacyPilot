package io.legacypilot.workspace;

import io.legacypilot.application.error.SourceAccessException;
import io.legacypilot.application.port.SourceFileReader;
import io.legacypilot.domain.project.Project;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Reads bounded UTF-8 blobs directly from an immutable Git tree. */
public final class GitSourceFileReader implements SourceFileReader {

  private static final int MAX_CONTEXT_LINES = 100;
  private static final int MAX_RETURNED_LINES = 2_000;
  private final long maxFileBytes;
  private final Duration timeout;

  public GitSourceFileReader(long maxFileBytes, Duration timeout) {
    if (maxFileBytes < 1 || timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("Source limits and timeout must be positive");
    }
    this.maxFileBytes = maxFileBytes;
    this.timeout = timeout;
  }

  @Override
  public SourceSnippet read(Project project, SourceReadRequest request) {
    if (!project.baseRevision().value().equalsIgnoreCase(request.revision())) {
      throw rejected(
          SourceAccessException.Reason.REVISION_MISMATCH,
          "Requested revision does not match the registered project revision");
    }
    var path = validatePath(request.path());
    validateRange(request);
    var repository = validateRepositoryRoot(project.repositoryPath());
    var object = request.revision() + ":" + path;
    rejectSymbolicLink(repository, request.revision(), path);
    var size = parseSize(runText(repository, List.of("cat-file", "-s", object)));
    if (size > maxFileBytes) {
      throw rejected(
          SourceAccessException.Reason.FILE_TOO_LARGE,
          "Source file exceeds the configured size limit");
    }
    var bytes = runBytes(repository, List.of("cat-file", "blob", object));
    if (bytes.length != size) {
      throw rejected(
          SourceAccessException.Reason.READ_FAILED, "Git blob size changed while reading");
    }
    var content = decodeText(bytes);
    var lines = splitLines(content);
    if (!request.fullFile() && request.endLine() > lines.size()) {
      throw rejected(
          SourceAccessException.Reason.INVALID_PATH, "Source line range exceeds the file");
    }
    var range = selectRange(request, lines.size());
    var snippet = String.join("\n", lines.subList(range.start() - 1, range.end()));
    var requested =
        request.fullFile()
            ? snippet
            : String.join("\n", lines.subList(request.startLine() - 1, request.endLine()));
    return new SourceSnippet(
        project.id().value(),
        project.baseRevision().value(),
        path,
        range.start(),
        range.end(),
        lines.size(),
        snippet,
        sha256(bytes),
        sha256(requested.getBytes(StandardCharsets.UTF_8)),
        sha256(snippet.getBytes(StandardCharsets.UTF_8)),
        range.start() > 1 || range.end() < lines.size());
  }

  private static String validatePath(String value) {
    if (value == null || value.isBlank() || value.length() > 4096 || value.indexOf('\0') >= 0) {
      throw rejected(SourceAccessException.Reason.INVALID_PATH, "Source path is invalid");
    }
    if (value.indexOf('\\') >= 0 || value.chars().anyMatch(Character::isISOControl)) {
      throw rejected(SourceAccessException.Reason.INVALID_PATH, "Source path is invalid");
    }
    var candidate = Path.of(value);
    if (candidate.isAbsolute() || candidate.getNameCount() == 0 || containsTraversal(candidate)) {
      throw rejected(
          SourceAccessException.Reason.INVALID_PATH,
          "Source path must be a repository-relative path without traversal");
    }
    var normalized = candidate.normalize().toString().replace(java.io.File.separatorChar, '/');
    if (normalized.equals(".") || normalized.startsWith("../")) {
      throw rejected(SourceAccessException.Reason.INVALID_PATH, "Source path escapes its root");
    }
    return normalized;
  }

  private static boolean containsTraversal(Path candidate) {
    for (var part : candidate) {
      if (part.toString().equals("..")) {
        return true;
      }
    }
    return false;
  }

  private static void validateRange(SourceReadRequest request) {
    if (request.contextLines() < 0 || request.contextLines() > MAX_CONTEXT_LINES) {
      throw rejected(
          SourceAccessException.Reason.RANGE_TOO_LARGE,
          "Source context must be between 0 and " + MAX_CONTEXT_LINES + " lines");
    }
    if (!request.fullFile()
        && (request.startLine() < 1 || request.endLine() < request.startLine())) {
      throw rejected(SourceAccessException.Reason.INVALID_PATH, "Source line range is invalid");
    }
  }

  private static Path validateRepositoryRoot(String value) {
    try {
      var configured = Path.of(value).toAbsolutePath().normalize();
      if (Files.isSymbolicLink(configured)) {
        throw rejected(
            SourceAccessException.Reason.INVALID_PATH,
            "Registered repository root must not be a symbolic link");
      }
      var real = configured.toRealPath();
      if (!Files.isDirectory(real)) {
        throw rejected(
            SourceAccessException.Reason.INVALID_PATH, "Registered repository root is unavailable");
      }
      return real;
    } catch (IOException | RuntimeException exception) {
      if (exception instanceof SourceAccessException sourceAccessException) {
        throw sourceAccessException;
      }
      throw new SourceAccessException(
          SourceAccessException.Reason.READ_FAILED,
          "Registered repository root is unavailable",
          exception);
    }
  }

  private void rejectSymbolicLink(Path repository, String revision, String path) {
    var entry = runText(repository, List.of("ls-tree", revision, "--", path));
    if (entry.isBlank()) {
      throw rejected(SourceAccessException.Reason.FILE_NOT_FOUND, "Source file was not found");
    }
    var separator = entry.indexOf('\t');
    if (separator < 0) {
      throw rejected(SourceAccessException.Reason.READ_FAILED, "Git tree entry is invalid");
    }
    var metadata = entry.substring(0, separator).split(" ");
    if (metadata.length < 2 || !metadata[1].equals("blob")) {
      throw rejected(SourceAccessException.Reason.FILE_NOT_FOUND, "Source path is not a file");
    }
    if (metadata[0].equals("120000")) {
      throw rejected(
          SourceAccessException.Reason.SYMBOLIC_LINK,
          "Symbolic-link source files are not readable");
    }
  }

  private static long parseSize(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException exception) {
      throw new SourceAccessException(
          SourceAccessException.Reason.READ_FAILED,
          "Unable to inspect source file size",
          exception);
    }
  }

  private static String decodeText(byte[] bytes) {
    for (var value : bytes) {
      if (value == 0) {
        throw rejected(
            SourceAccessException.Reason.BINARY_FILE, "Binary source files are not readable");
      }
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (java.nio.charset.CharacterCodingException exception) {
      throw new SourceAccessException(
          SourceAccessException.Reason.BINARY_FILE,
          "Source file is not valid UTF-8 text",
          exception);
    }
  }

  private static List<String> splitLines(String content) {
    var lines = new ArrayList<>(List.of(content.split("\\R", -1)));
    if (lines.size() > 1 && lines.getLast().isEmpty()) {
      lines.removeLast();
    }
    return lines.isEmpty() ? List.of("") : List.copyOf(lines);
  }

  private static Range selectRange(SourceReadRequest request, int totalLines) {
    if (request.fullFile()) {
      if (totalLines > MAX_RETURNED_LINES) {
        throw rejected(
            SourceAccessException.Reason.RANGE_TOO_LARGE,
            "Source file exceeds the full-view line limit");
      }
      return new Range(1, totalLines);
    }
    if (request.startLine() > totalLines) {
      throw rejected(
          SourceAccessException.Reason.INVALID_PATH, "Source line range exceeds the file");
    }
    var start = Math.max(1, request.startLine() - request.contextLines());
    var end = Math.min(totalLines, request.endLine() + request.contextLines());
    if (end - start + 1 > MAX_RETURNED_LINES) {
      throw rejected(
          SourceAccessException.Reason.RANGE_TOO_LARGE,
          "Requested source range exceeds the line limit");
    }
    return new Range(start, end);
  }

  private String runText(Path repository, List<String> arguments) {
    return new String(runBytes(repository, arguments), StandardCharsets.UTF_8).strip();
  }

  private byte[] runBytes(Path repository, List<String> arguments) {
    var command = new ArrayList<String>();
    command.add("git");
    command.add("-C");
    command.add(repository.toString());
    command.addAll(arguments);
    try {
      var process = new ProcessBuilder(command).redirectErrorStream(true).start();
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var output = executor.submit(() -> process.getInputStream().readAllBytes());
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
          process.destroyForcibly();
          process.waitFor();
          throw rejected(SourceAccessException.Reason.READ_FAILED, "Git source read timed out");
        }
        var bytes = output.get();
        if (process.exitValue() != 0) {
          throw rejected(SourceAccessException.Reason.FILE_NOT_FOUND, "Source file was not found");
        }
        return bytes;
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new SourceAccessException(
          SourceAccessException.Reason.READ_FAILED, "Git source read was interrupted", exception);
    } catch (IOException | java.util.concurrent.ExecutionException exception) {
      throw new SourceAccessException(
          SourceAccessException.Reason.READ_FAILED, "Unable to read Git source", exception);
    }
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private static SourceAccessException rejected(
      SourceAccessException.Reason reason, String message) {
    return new SourceAccessException(reason, message);
  }

  private record Range(int start, int end) {}
}

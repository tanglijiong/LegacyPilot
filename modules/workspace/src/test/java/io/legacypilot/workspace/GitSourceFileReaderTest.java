package io.legacypilot.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.legacypilot.application.error.SourceAccessException;
import io.legacypilot.application.port.SourceFileReader.SourceReadRequest;
import io.legacypilot.domain.project.GitRevision;
import io.legacypilot.domain.project.Project;
import io.legacypilot.domain.project.ProjectId;
import io.legacypilot.domain.project.RepositoryLocation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitSourceFileReaderTest {

  @TempDir Path temporaryDirectory;
  private Path repository;
  private Project project;

  @BeforeEach
  void createRepository() throws IOException {
    repository = temporaryDirectory.resolve("source");
    Files.createDirectories(repository);
    git("init", "--initial-branch=main");
    git("config", "user.name", "LegacyPilot Test");
    git("config", "user.email", "test@legacypilot.invalid");
    Files.writeString(repository.resolve("source.txt"), "one\ntwo\nthree\nfour\nfive\n");
    Files.write(repository.resolve("binary.dat"), new byte[] {1, 0, 2});
    Files.write(repository.resolve("invalid-utf8.dat"), new byte[] {(byte) 0xc3, 0x28});
    Files.createDirectories(repository.resolve("directory"));
    Files.writeString(repository.resolve("directory/child.txt"), "child\n");
    Files.writeString(repository.resolve("many-lines.txt"), "line\n".repeat(2_001));
    Files.createSymbolicLink(repository.resolve("outside-link"), Path.of("/etc/passwd"));
    git(
        "add",
        "source.txt",
        "binary.dat",
        "invalid-utf8.dat",
        "directory/child.txt",
        "many-lines.txt",
        "outside-link");
    git("commit", "-m", "source fixture");
    var revision = git("rev-parse", "HEAD");
    project =
        new Project(
            new ProjectId("project-1"),
            new RepositoryLocation(repository.toString()),
            repository.toString(),
            new GitRevision(revision),
            Instant.EPOCH);
  }

  @Test
  void readsContextFromPinnedRevisionAndReturnsStableHashes() throws IOException {
    var reader = new GitSourceFileReader(1024, Duration.ofSeconds(5));
    Files.writeString(repository.resolve("source.txt"), "changed working tree\n");

    var result =
        reader.read(
            project,
            new SourceReadRequest(project.baseRevision().value(), "source.txt", 3, 3, 1, false));

    assertEquals("two\nthree\nfour", result.content());
    assertEquals(2, result.startLine());
    assertEquals(4, result.endLine());
    assertEquals(5, result.totalLines());
    assertTrue(result.contentSha256().matches("[0-9a-f]{64}"));
    assertTrue(result.requestedRangeSha256().matches("[0-9a-f]{64}"));
    assertTrue(result.snippetSha256().matches("[0-9a-f]{64}"));
    assertTrue(result.truncated());

    var full =
        reader.read(
            project,
            new SourceReadRequest(project.baseRevision().value(), "source.txt", 1, 1, 0, true));
    assertEquals("one\ntwo\nthree\nfour\nfive", full.content());
    assertFalse(full.truncated());
    assertEquals(result.contentSha256(), full.contentSha256());
  }

  @Test
  void rejectsTraversalAbsoluteSymlinkBinaryLargeAndInvalidRanges() {
    var reader = new GitSourceFileReader(1024, Duration.ofSeconds(5));
    assertReason(reader, "../source.txt", SourceAccessException.Reason.INVALID_PATH);
    assertReason(
        reader,
        repository.resolve("source.txt").toString(),
        SourceAccessException.Reason.INVALID_PATH);
    assertReason(reader, "outside-link", SourceAccessException.Reason.SYMBOLIC_LINK);
    assertReason(reader, "binary.dat", SourceAccessException.Reason.BINARY_FILE);
    assertReason(reader, "invalid-utf8.dat", SourceAccessException.Reason.BINARY_FILE);
    assertReason(reader, "missing.txt", SourceAccessException.Reason.FILE_NOT_FOUND);
    assertReason(reader, "directory", SourceAccessException.Reason.FILE_NOT_FOUND);
    assertReason(reader, "", SourceAccessException.Reason.INVALID_PATH);
    assertReason(reader, "bad\npath", SourceAccessException.Reason.INVALID_PATH);
    var wrongRevision =
        assertThrows(
            SourceAccessException.class,
            () ->
                reader.read(
                    project, new SourceReadRequest("f".repeat(40), "source.txt", 1, 1, 0, false)));
    assertEquals(SourceAccessException.Reason.REVISION_MISMATCH, wrongRevision.reason());

    var smallReader = new GitSourceFileReader(3, Duration.ofSeconds(5));
    assertReason(smallReader, "source.txt", SourceAccessException.Reason.FILE_TOO_LARGE);
    var exception =
        assertThrows(
            SourceAccessException.class,
            () ->
                reader.read(
                    project,
                    new SourceReadRequest(
                        project.baseRevision().value(), "source.txt", 1, 1, 101, false)));
    assertEquals(SourceAccessException.Reason.RANGE_TOO_LARGE, exception.reason());
    var invalidRange =
        assertThrows(
            SourceAccessException.class,
            () ->
                reader.read(
                    project,
                    new SourceReadRequest(
                        project.baseRevision().value(), "source.txt", 3, 2, 0, false)));
    assertEquals(SourceAccessException.Reason.INVALID_PATH, invalidRange.reason());
    var beyondFile =
        assertThrows(
            SourceAccessException.class,
            () ->
                reader.read(
                    project,
                    new SourceReadRequest(
                        project.baseRevision().value(), "source.txt", 5, 6, 0, false)));
    assertEquals(SourceAccessException.Reason.INVALID_PATH, beyondFile.reason());
    var fullTooLarge =
        assertThrows(
            SourceAccessException.class,
            () ->
                new GitSourceFileReader(20_000, Duration.ofSeconds(5))
                    .read(
                        project,
                        new SourceReadRequest(
                            project.baseRevision().value(), "many-lines.txt", 1, 1, 0, true)));
    assertEquals(SourceAccessException.Reason.RANGE_TOO_LARGE, fullTooLarge.reason());
    assertThrows(
        IllegalArgumentException.class, () -> new GitSourceFileReader(0, Duration.ofSeconds(5)));
    assertThrows(IllegalArgumentException.class, () -> new GitSourceFileReader(1, Duration.ZERO));
  }

  private void assertReason(
      GitSourceFileReader reader, String path, SourceAccessException.Reason reason) {
    var exception =
        assertThrows(
            SourceAccessException.class,
            () ->
                reader.read(
                    project,
                    new SourceReadRequest(project.baseRevision().value(), path, 1, 1, 0, false)));
    assertEquals(reason, exception.reason());
  }

  private String git(String... arguments) {
    var command = new java.util.ArrayList<String>();
    command.add("git");
    command.addAll(List.of(arguments));
    try {
      var process = new ProcessBuilder(command).directory(repository.toFile()).start();
      var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      var error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.waitFor() != 0) {
        throw new IllegalStateException(error);
      }
      return output.strip();
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }
}

package io.legacypilot.tool.filesystem;

import io.legacypilot.tool.spi.ToolErrorCode;
import io.legacypilot.tool.spi.ToolFailureException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class WorkspacePaths {

  private WorkspacePaths() {}

  static Path existing(Path workspace, String requested) {
    var candidate = lexical(workspace, requested);
    try {
      var realRoot = workspace.toRealPath();
      var realCandidate = candidate.toRealPath();
      if (!realCandidate.startsWith(realRoot)) {
        throw violation();
      }
      rejectHardLink(realCandidate);
      return realCandidate;
    } catch (IOException exception) {
      throw new ToolFailureException(
          ToolErrorCode.PATH_VIOLATION, "Requested path is not accessible", exception);
    }
  }

  static Path writable(Path workspace, String requested) {
    var candidate = lexical(workspace, requested);
    var parent = candidate.getParent();
    if (parent == null) {
      throw violation();
    }
    try {
      var realRoot = workspace.toRealPath();
      var realParent = parent.toRealPath();
      if (!realParent.startsWith(realRoot)) {
        throw violation();
      }
      if (Files.exists(candidate) && !candidate.toRealPath().startsWith(realRoot)) {
        throw violation();
      }
      if (Files.exists(candidate)) {
        rejectHardLink(candidate);
      }
      return candidate;
    } catch (IOException exception) {
      throw new ToolFailureException(
          ToolErrorCode.PATH_VIOLATION, "Requested path is not writable", exception);
    }
  }

  static String relative(Path workspace, Path file) {
    try {
      return workspace.toRealPath().relativize(file.toRealPath()).toString();
    } catch (IOException exception) {
      throw new ToolFailureException(
          ToolErrorCode.PATH_VIOLATION, "Unable to resolve workspace-relative path", exception);
    }
  }

  private static Path lexical(Path workspace, String requested) {
    if (requested == null || requested.isBlank()) {
      throw violation();
    }
    var path = Path.of(requested);
    if (path.isAbsolute()) {
      throw violation();
    }
    var root = workspace.toAbsolutePath().normalize();
    var candidate = root.resolve(path).normalize();
    if (!candidate.startsWith(root) || candidate.equals(root)) {
      throw violation();
    }
    return candidate;
  }

  private static ToolFailureException violation() {
    return new ToolFailureException(
        ToolErrorCode.PATH_VIOLATION, "Path escapes the current workspace");
  }

  private static void rejectHardLink(Path path) throws IOException {
    try {
      var links = (Number) Files.getAttribute(path, "unix:nlink");
      if (links.longValue() > 1) {
        throw violation();
      }
    } catch (UnsupportedOperationException exception) {
      // Non-Unix file systems do not expose link counts; real-path containment remains enforced.
    }
  }
}

package io.legacypilot.tool.filesystem;

import io.legacypilot.tool.spi.ToolErrorCode;
import io.legacypilot.tool.spi.ToolFailureException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class PatchSupport {

  private PatchSupport() {}

  static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  static String preview(String oldValue, String newValue) {
    var oldLines = oldValue.lines().count();
    var newLines = newValue.lines().count();
    return "@@ full-file guarded replacement @@\n- "
        + oldLines
        + " lines\n+ "
        + newLines
        + " lines";
  }

  static String replaceLines(String current, int startLine, int endLine, String replacement) {
    if (startLine < 1 || endLine < startLine) {
      throw new ToolFailureException(
          ToolErrorCode.INVALID_INPUT_SCHEMA,
          "startLine and endLine must identify a valid inclusive line range");
    }
    var lines = current.split("\\n", -1);
    var logicalLineCount = current.endsWith("\n") ? lines.length - 1 : lines.length;
    if (endLine > logicalLineCount) {
      throw new ToolFailureException(
          ToolErrorCode.INVALID_INPUT_SCHEMA, "patch line range exceeds the current file");
    }
    var before = String.join("\n", java.util.Arrays.copyOfRange(lines, 0, startLine - 1));
    var after = String.join("\n", java.util.Arrays.copyOfRange(lines, endLine, lines.length));
    var result = new StringBuilder(current.length() + replacement.length());
    if (!before.isEmpty()) {
      result.append(before).append('\n');
    }
    result.append(replacement);
    if (!replacement.endsWith("\n") && !after.isEmpty()) {
      result.append('\n');
    }
    result.append(after);
    return result.toString();
  }

  static String lineRange(String current, int startLine, int endLine) {
    if (startLine < 1 || endLine < startLine) {
      throw new ToolFailureException(
          ToolErrorCode.INVALID_INPUT_SCHEMA,
          "startLine and endLine must identify a valid inclusive line range");
    }
    var lines = current.split("\\n", -1);
    var logicalLineCount = current.endsWith("\n") ? lines.length - 1 : lines.length;
    if (endLine > logicalLineCount) {
      throw new ToolFailureException(
          ToolErrorCode.INVALID_INPUT_SCHEMA, "patch line range exceeds the current file");
    }
    return String.join("\n", java.util.Arrays.copyOfRange(lines, startLine - 1, endLine));
  }
}

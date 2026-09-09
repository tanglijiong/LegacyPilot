package io.legacypilot.tool.filesystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.tool.spi.AgentTool;
import io.legacypilot.tool.spi.Idempotency;
import io.legacypilot.tool.spi.JsonSchemas;
import io.legacypilot.tool.spi.RiskLevel;
import io.legacypilot.tool.spi.ToolContext;
import io.legacypilot.tool.spi.ToolDescriptor;
import io.legacypilot.tool.spi.ToolErrorCode;
import io.legacypilot.tool.spi.ToolFailureException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

public final class CreatePatchTool implements AgentTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "create_patch",
          "Create a digest-bound patch without modifying the workspace. For an existing file, "
              + "prefer an inclusive startLine/endLine range and provide only that range's replacement.",
          JsonSchemas.parse(
              """
              {"type":"object","required":["path","replacement"],"additionalProperties":false,
               "properties":{"path":{"type":"string","maxLength":4096},
               "replacement":{"type":"string","maxLength":1048576},
               "startLine":{"type":"integer","minimum":1},
               "endLine":{"type":"integer","minimum":1,"maximum":200000}}}
              """),
          JsonSchemas.parse("{\"type\":\"object\"}"),
          RiskLevel.READ_ONLY,
          Idempotency.IDEMPOTENT,
          Duration.ofSeconds(5),
          2 * 1024 * 1024,
          2 * 1024 * 1024,
          Set.of());

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public JsonNode execute(ToolContext context, JsonNode input) {
    var path = WorkspacePaths.writable(context.workspaceRoot(), input.path("path").asText());
    try {
      var exists = Files.isRegularFile(path);
      var current = exists ? Files.readString(path, StandardCharsets.UTF_8) : "";
      var replacement = input.path("replacement").asText();
      rejectPlaceholder(replacement);
      var ranged = input.has("startLine") || input.has("endLine");
      if (ranged && (!input.has("startLine") || !input.has("endLine"))) {
        throw new ToolFailureException(
            ToolErrorCode.INVALID_INPUT_SCHEMA,
            "startLine and endLine must be provided together for a range patch");
      }
      if (ranged && !exists) {
        ranged = false;
      }
      if (ranged && path.toString().endsWith(".java")) {
        validateJavaRange(
            PatchSupport.lineRange(
                current, input.path("startLine").asInt(), input.path("endLine").asInt()),
            replacement);
      }
      var finalContent =
          ranged
              ? PatchSupport.replaceLines(
                  current,
                  input.path("startLine").asInt(),
                  input.path("endLine").asInt(),
                  replacement)
              : replacement;
      if (exists && finalContent.equals(current)) {
        throw new ToolFailureException(
            ToolErrorCode.INVALID_INPUT_SCHEMA,
            "patch does not change the file; propose a concrete source modification");
      }
      validateCompleteReplacement(path, current, finalContent, exists);
      var result = MAPPER.createObjectNode();
      result.put("path", context.workspaceRoot().relativize(path).toString());
      result.put("expectedSha256", PatchSupport.sha256(current));
      result.put("replacement", replacement);
      if (ranged) {
        result.put("startLine", input.path("startLine").asInt());
        result.put("endLine", input.path("endLine").asInt());
        result.put("patchMode", "RANGE");
      } else {
        result.put("patchMode", "FULL_FILE");
      }
      result.put("preview", PatchSupport.preview(current, finalContent));
      result.put("createsFile", !exists);
      return result;
    } catch (IOException exception) {
      throw new ToolFailureException(
          ToolErrorCode.COMMAND_FAILED, "Unable to create patch", exception);
    }
  }

  private static void validateJavaRange(String original, String replacement) {
    var originalFirst = original.lines().filter(line -> !line.isBlank()).findFirst().orElse("");
    var replacementFirst =
        replacement.lines().filter(line -> !line.isBlank()).findFirst().orElse("");
    if (indent(originalFirst) != indent(replacementFirst)) {
      throw new ToolFailureException(
          ToolErrorCode.INVALID_INPUT_SCHEMA,
          "Java range replacement crosses a structural boundary; preserve the first line's indentation");
    }
    if (braceDelta(original) != braceDelta(replacement)) {
      throw new ToolFailureException(
          ToolErrorCode.INVALID_INPUT_SCHEMA,
          "Java range replacement must preserve the surrounding brace balance");
    }
  }

  private static int indent(String line) {
    var count = 0;
    while (count < line.length() && Character.isWhitespace(line.charAt(count))) {
      count++;
    }
    return count;
  }

  private static int braceDelta(String source) {
    var delta = 0;
    for (var index = 0; index < source.length(); index++) {
      if (source.charAt(index) == '{') {
        delta++;
      } else if (source.charAt(index) == '}') {
        delta--;
      }
    }
    return delta;
  }

  private static void rejectPlaceholder(String replacement) {
    var normalized = replacement.toLowerCase(java.util.Locale.ROOT);
    if (normalized.matches(
        "(?s).*(其他代码|其余代码|代码省略|省略代码|保持不变|existing code|rest of (the )?code|unchanged code|code omitted).*")) {
      throw new ToolFailureException(
          ToolErrorCode.INVALID_INPUT_SCHEMA,
          "replacement contains an omitted-code placeholder; provide executable source only");
    }
  }

  private static void validateCompleteReplacement(
      Path path, String current, String replacement, boolean exists) {
    if (replacement.isBlank()) {
      throw new ToolFailureException(
          ToolErrorCode.INVALID_INPUT_SCHEMA, "replacement must contain complete file contents");
    }
    if (exists && replacement.length() * 5 < current.length()) {
      throw new ToolFailureException(
          ToolErrorCode.INVALID_INPUT_SCHEMA,
          "replacement is suspiciously short; provide the complete file instead of a summary or placeholder");
    }
    if (path.toString().endsWith(".java")) {
      if (exists && current.contains("package ") && !replacement.contains("package ")) {
        throw new ToolFailureException(
            ToolErrorCode.INVALID_INPUT_SCHEMA,
            "Java replacement must preserve the package declaration");
      }
      var expectedPackage = expectedJavaPackage(path);
      if (!exists
          && !expectedPackage.isBlank()
          && !replacement.matches(
              "(?s).*\\bpackage\\s+"
                  + java.util.regex.Pattern.quote(expectedPackage)
                  + "\\s*;.*")) {
        throw new ToolFailureException(
            ToolErrorCode.INVALID_INPUT_SCHEMA,
            "new Java file must declare package " + expectedPackage);
      }
      if (!replacement.matches(
          "(?s).*\\b(class|interface|enum|record)\\s+[A-Za-z_$][A-Za-z0-9_$]*.*")) {
        throw new ToolFailureException(
            ToolErrorCode.INVALID_INPUT_SCHEMA,
            "Java replacement must contain a complete type declaration");
      }
      if (braceDelta(replacement) != 0 || !replacement.stripTrailing().endsWith("}")) {
        throw new ToolFailureException(
            ToolErrorCode.INVALID_INPUT_SCHEMA,
            "Java replacement is structurally incomplete; provide the complete source file");
      }
    }
  }

  private static String expectedJavaPackage(Path path) {
    var normalized = path.toString().replace('\\', '/');
    var marker = "/src/main/java/";
    var start = normalized.lastIndexOf(marker);
    if (start < 0) {
      return "";
    }
    var relative = normalized.substring(start + marker.length());
    var separator = relative.lastIndexOf('/');
    return separator < 0 ? "" : relative.substring(0, separator).replace('/', '.');
  }
}

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
import java.time.Duration;
import java.util.Set;

public final class ReadFileTool implements AgentTool {

  private static final int MAX_LINES = 2_000;
  private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "read_file",
          "Read a bounded UTF-8 text range inside the current workspace.",
          JsonSchemas.parse(
              """
              {"type":"object","required":["path"],"additionalProperties":false,
               "properties":{"path":{"type":"string","maxLength":4096},
               "startLine":{"type":"integer"},"endLine":{"type":"integer"}}}
              """),
          JsonSchemas.parse("{" + "\"type\":\"object\"}"),
          RiskLevel.READ_ONLY,
          Idempotency.IDEMPOTENT,
          Duration.ofSeconds(5),
          8 * 1024,
          512 * 1024,
          Set.of());

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public JsonNode execute(ToolContext context, JsonNode input) {
    var path = WorkspacePaths.existing(context.workspaceRoot(), input.path("path").asText());
    if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
      throw new ToolFailureException(ToolErrorCode.PATH_VIOLATION, "Path is not a regular file");
    }
    try {
      if (Files.size(path) > MAX_FILE_BYTES) {
        throw new ToolFailureException(
            ToolErrorCode.INPUT_TOO_LARGE, "Workspace file exceeds the readable size limit");
      }
    } catch (IOException exception) {
      throw new ToolFailureException(
          ToolErrorCode.COMMAND_FAILED, "Unable to inspect workspace file", exception);
    }
    var start = Math.max(1, input.path("startLine").asInt(1));
    var requestedEnd = input.path("endLine").asInt(start + MAX_LINES - 1);
    var end = Math.min(requestedEnd, start + MAX_LINES - 1);
    if (end < start) {
      throw new ToolFailureException(
          ToolErrorCode.INVALID_INPUT_SCHEMA, "endLine must not precede startLine");
    }
    try {
      var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
      var from = Math.min(start - 1, lines.size());
      var to = Math.min(end, lines.size());
      var output = MAPPER.createObjectNode();
      output.put("path", WorkspacePaths.relative(context.workspaceRoot(), path));
      output.put("startLine", start);
      output.put("endLine", to);
      output.put("content", String.join("\n", lines.subList(from, to)));
      output.put("truncated", requestedEnd > end || to < lines.size());
      return output;
    } catch (IOException exception) {
      throw new ToolFailureException(
          ToolErrorCode.COMMAND_FAILED, "Unable to read workspace file", exception);
    }
  }
}

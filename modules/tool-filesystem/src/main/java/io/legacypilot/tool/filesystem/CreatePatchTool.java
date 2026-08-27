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

public final class CreatePatchTool implements AgentTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "create_patch",
          "Create a content-digest-bound replacement or new-file patch without modifying the workspace.",
          JsonSchemas.parse(
              """
              {"type":"object","required":["path","replacement"],"additionalProperties":false,
               "properties":{"path":{"type":"string","maxLength":4096},
               "replacement":{"type":"string","maxLength":1048576}}}
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
      var result = MAPPER.createObjectNode();
      result.put("path", context.workspaceRoot().relativize(path).toString());
      result.put("expectedSha256", PatchSupport.sha256(current));
      result.put("replacement", replacement);
      result.put("preview", PatchSupport.preview(current, replacement));
      result.put("createsFile", !exists);
      return result;
    } catch (IOException exception) {
      throw new ToolFailureException(
          ToolErrorCode.COMMAND_FAILED, "Unable to create patch", exception);
    }
  }
}

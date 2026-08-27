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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Set;

public final class ApplyPatchTool implements AgentTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "apply_patch",
          "Atomically apply an approved digest-bound patch inside allowed workspace files.",
          JsonSchemas.parse(
              """
              {"type":"object","required":["path","expectedSha256","replacement"],
               "additionalProperties":false,"properties":{
               "path":{"type":"string","maxLength":4096},
               "expectedSha256":{"type":"string","pattern":"[0-9a-f]{64}"},
               "replacement":{"type":"string","maxLength":1048576}}}
              """),
          JsonSchemas.parse("{\"type\":\"object\"}"),
          RiskLevel.WORKSPACE_WRITE,
          Idempotency.CONDITIONAL,
          Duration.ofSeconds(10),
          2 * 1024 * 1024,
          64 * 1024,
          Set.of());

  private final List<String> writableGlobs;

  public ApplyPatchTool(List<String> writableGlobs) {
    this.writableGlobs = List.copyOf(writableGlobs);
    if (writableGlobs.isEmpty()) {
      throw new IllegalArgumentException("At least one writable glob is required");
    }
  }

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public JsonNode execute(ToolContext context, JsonNode input) {
    var relative = input.path("path").asText();
    if (!allowed(relative)) {
      throw new ToolFailureException(
          ToolErrorCode.PATH_VIOLATION, "Patch target is outside configured writable globs");
    }
    var path = WorkspacePaths.writable(context.workspaceRoot(), relative);
    try {
      var current = Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
      if (!PatchSupport.sha256(current).equals(input.path("expectedSha256").asText())) {
        throw new ToolFailureException(
            ToolErrorCode.PATCH_CONFLICT, "Patch context no longer matches the workspace file");
      }
      var replacement = input.path("replacement").asText();
      var parent = java.util.Objects.requireNonNull(path.getParent(), "patch parent");
      var temporary = Files.createTempFile(parent, ".legacypilot-patch-", ".tmp");
      try {
        Files.writeString(temporary, replacement, StandardCharsets.UTF_8);
        Files.move(
            temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temporary);
      }
      var result = MAPPER.createObjectNode();
      result.put("path", relative);
      result.put("sha256", PatchSupport.sha256(replacement));
      result.put("bytesWritten", replacement.getBytes(StandardCharsets.UTF_8).length);
      return result;
    } catch (ToolFailureException exception) {
      throw exception;
    } catch (IOException exception) {
      throw new ToolFailureException(
          ToolErrorCode.COMMAND_FAILED, "Unable to apply patch", exception);
    }
  }

  private boolean allowed(String relative) {
    var path = java.nio.file.Path.of(relative).normalize();
    return writableGlobs.stream()
        .anyMatch(
            glob -> {
              if (glob.endsWith("/**")) {
                var prefix = java.nio.file.Path.of(glob.substring(0, glob.length() - 3));
                return path.startsWith(prefix) && !path.equals(prefix);
              }
              return FileSystems.getDefault().getPathMatcher("glob:" + glob).matches(path);
            });
  }
}

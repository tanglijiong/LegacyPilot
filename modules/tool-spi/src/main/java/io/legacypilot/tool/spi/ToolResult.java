package io.legacypilot.tool.spi;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Objects;

public record ToolResult(
    ToolStatus status,
    JsonNode output,
    ToolError error,
    String actionDigest,
    Duration duration,
    boolean outputTruncated) {

  public ToolResult {
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(actionDigest, "actionDigest must not be null");
    Objects.requireNonNull(duration, "duration must not be null");
    output = output == null ? null : output.deepCopy();
  }

  @Override
  public JsonNode output() {
    return output == null ? null : output.deepCopy();
  }

  public boolean successful() {
    return status == ToolStatus.SUCCESS;
  }
}

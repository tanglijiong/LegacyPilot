package io.legacypilot.tool.spi;

import java.util.Objects;

public record ToolError(ToolErrorCode code, String message) {
  public ToolError {
    Objects.requireNonNull(code, "code must not be null");
    Objects.requireNonNull(message, "message must not be null");
  }
}

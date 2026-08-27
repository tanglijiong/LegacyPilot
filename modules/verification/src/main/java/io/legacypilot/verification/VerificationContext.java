package io.legacypilot.verification;

import io.legacypilot.tool.spi.ToolContext;
import io.legacypilot.tool.spi.ToolExecutor;
import java.nio.file.Path;
import java.util.Objects;

public record VerificationContext(Path workspace, ToolContext toolContext, ToolExecutor tools) {

  public VerificationContext {
    workspace = Objects.requireNonNull(workspace).toAbsolutePath().normalize();
    Objects.requireNonNull(toolContext);
    Objects.requireNonNull(tools);
  }
}

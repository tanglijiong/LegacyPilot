package io.legacypilot.tool.spi;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

public record ToolContext(
    String runId,
    Path workspaceRoot,
    Set<String> approvedActionDigests,
    boolean commandExecutionAllowed) {

  public ToolContext {
    Objects.requireNonNull(runId, "runId must not be null");
    Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
    Objects.requireNonNull(approvedActionDigests, "approvedActionDigests must not be null");
    if (runId.isBlank()) {
      throw new IllegalArgumentException("runId must not be blank");
    }
    workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    approvedActionDigests = Set.copyOf(approvedActionDigests);
  }
}

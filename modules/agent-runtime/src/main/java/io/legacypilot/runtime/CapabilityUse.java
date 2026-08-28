package io.legacypilot.runtime;

import java.nio.file.Path;
import java.util.Objects;

public record CapabilityUse(
    String subject,
    String sessionId,
    String runId,
    String tool,
    Path workspace,
    String actionDigest,
    String planDigest) {
  public CapabilityUse {
    Objects.requireNonNull(subject);
    Objects.requireNonNull(sessionId);
    Objects.requireNonNull(runId);
    Objects.requireNonNull(tool);
    workspace = Objects.requireNonNull(workspace).toAbsolutePath().normalize();
    Objects.requireNonNull(actionDigest);
    planDigest = Objects.requireNonNullElse(planDigest, "");
  }
}

package io.legacypilot.runtime;

import io.legacypilot.model.ModelUsage;
import java.time.Instant;
import java.util.Objects;

public record AgentCheckpoint(
    String runId,
    RuntimeStatus status,
    ChangePlan plan,
    int steps,
    int retries,
    ModelUsage usage,
    Instant startedAt,
    Instant updatedAt,
    AgentAction pendingAction,
    String lastFailedDigest,
    int repeatedFailures,
    String observation) {

  public AgentCheckpoint {
    Objects.requireNonNull(runId);
    Objects.requireNonNull(status);
    Objects.requireNonNull(usage);
    Objects.requireNonNull(startedAt);
    Objects.requireNonNull(updatedAt);
    lastFailedDigest = Objects.requireNonNullElse(lastFailedDigest, "");
    observation = Objects.requireNonNullElse(observation, "");
    if (runId.isBlank() || steps < 0 || retries < 0 || repeatedFailures < 0) {
      throw new IllegalArgumentException("checkpoint is invalid");
    }
  }
}

package io.legacypilot.eval;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record EvalExperimentCheckpoint(
    String runId, Status status, Map<String, EvalAttemptCheckpoint> attempts, Instant updatedAt) {
  public EvalExperimentCheckpoint {
    Objects.requireNonNull(runId);
    Objects.requireNonNull(status);
    attempts =
        java.util.Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(attempts)));
    Objects.requireNonNull(updatedAt);
    if (runId.isBlank() || attempts.isEmpty()) {
      throw new IllegalArgumentException("eval experiment checkpoint is invalid");
    }
  }

  public enum Status {
    RUNNING,
    COMPLETED,
    BUDGET_EXHAUSTED,
    NEEDS_REVIEW
  }
}

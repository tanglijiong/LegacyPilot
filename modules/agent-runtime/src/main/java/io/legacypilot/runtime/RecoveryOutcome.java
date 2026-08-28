package io.legacypilot.runtime;

import java.util.Objects;

public record RecoveryOutcome(
    String runId, Decision decision, RuntimeStatus status, String detail) {
  public enum Decision {
    RESUMED,
    AWAITING_APPROVAL,
    NEEDS_REVIEW,
    TERMINAL,
    FAILED
  }

  public RecoveryOutcome {
    Objects.requireNonNull(runId);
    Objects.requireNonNull(decision);
    Objects.requireNonNull(status);
    detail = Objects.requireNonNullElse(detail, "");
  }
}

package io.legacypilot.runtime;

import java.time.Instant;
import java.util.Objects;

public record RuntimeApproval(
    String runId,
    String actionDigest,
    String planDigest,
    String actor,
    Decision decision,
    ApprovalScope scope,
    String reason,
    Instant expiresAt) {

  public RuntimeApproval {
    Objects.requireNonNull(runId);
    Objects.requireNonNull(actionDigest);
    Objects.requireNonNull(planDigest);
    Objects.requireNonNull(actor);
    Objects.requireNonNull(decision);
    Objects.requireNonNull(scope);
    reason = Objects.requireNonNullElse(reason, "");
    Objects.requireNonNull(expiresAt);
    if (runId.isBlank() || actionDigest.isBlank() || actor.isBlank()) {
      throw new IllegalArgumentException("approval identity is invalid");
    }
  }

  public boolean matches(String run, String action, String plan, Instant now) {
    return runId.equals(run)
        && !now.isAfter(expiresAt)
        && (actionDigest.equals(action)
            || (scope == ApprovalScope.MATCHING_PLAN && planDigest.equals(plan)));
  }

  public enum Decision {
    APPROVED,
    DENIED
  }
}

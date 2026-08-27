package io.legacypilot.domain.run;

import java.time.Instant;
import java.util.Objects;

public record Approval(
    String actionDigest, Decision decision, String actor, String reason, Instant expiresAt) {

  public Approval {
    Objects.requireNonNull(actionDigest, "actionDigest must not be null");
    Objects.requireNonNull(decision, "decision must not be null");
    Objects.requireNonNull(actor, "actor must not be null");
    reason = reason == null ? "" : reason;
  }

  public enum Decision {
    APPROVED,
    DENIED
  }
}

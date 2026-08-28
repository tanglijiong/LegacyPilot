package io.legacypilot.tool.spi;

import java.util.Objects;

public record PolicyDecision(
    Effect effect,
    String reason,
    String actionDigest,
    String ruleId,
    String policyRevision,
    String requiredScope) {

  public PolicyDecision(Effect effect, String reason, String actionDigest) {
    this(effect, reason, actionDigest, "legacy-default", "legacy", "");
  }

  public PolicyDecision {
    Objects.requireNonNull(effect, "effect must not be null");
    Objects.requireNonNull(reason, "reason must not be null");
    Objects.requireNonNull(actionDigest, "actionDigest must not be null");
    Objects.requireNonNull(ruleId, "ruleId must not be null");
    Objects.requireNonNull(policyRevision, "policyRevision must not be null");
    Objects.requireNonNull(requiredScope, "requiredScope must not be null");
    if (ruleId.isBlank() || policyRevision.isBlank()) {
      throw new IllegalArgumentException("policy rule id and revision must not be blank");
    }
  }

  public enum Effect {
    ALLOW,
    DENY,
    REQUIRE_APPROVAL
  }
}

package io.legacypilot.tool.spi;

import java.util.Objects;

public record PolicyDecision(Effect effect, String reason, String actionDigest) {

  public PolicyDecision {
    Objects.requireNonNull(effect, "effect must not be null");
    Objects.requireNonNull(reason, "reason must not be null");
    Objects.requireNonNull(actionDigest, "actionDigest must not be null");
  }

  public enum Effect {
    ALLOW,
    DENY,
    REQUIRE_APPROVAL
  }
}

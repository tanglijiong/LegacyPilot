package io.legacypilot.context;

import java.util.Objects;

public record ContextDecision(String symbolId, String reason) {

  public ContextDecision {
    Objects.requireNonNull(symbolId, "symbolId must not be null");
    Objects.requireNonNull(reason, "reason must not be null");
  }
}

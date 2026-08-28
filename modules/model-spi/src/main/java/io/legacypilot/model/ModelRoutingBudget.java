package io.legacypilot.model;

import java.math.BigDecimal;
import java.util.Objects;

public record ModelRoutingBudget(
    int maximumAttempts, int maximumTokens, BigDecimal maximumCostUsd) {
  public ModelRoutingBudget {
    Objects.requireNonNull(maximumCostUsd);
    if (maximumAttempts < 1
        || maximumAttempts > 10
        || maximumTokens < 1
        || maximumCostUsd.signum() < 0) {
      throw new IllegalArgumentException("model routing budget is invalid");
    }
  }

  public static ModelRoutingBudget safeDefaults() {
    return new ModelRoutingBudget(3, 100_000, new BigDecimal("10"));
  }
}

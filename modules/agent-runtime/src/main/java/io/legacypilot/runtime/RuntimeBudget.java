package io.legacypilot.runtime;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

public record RuntimeBudget(
    int maximumSteps,
    int maximumRetries,
    int maximumTokens,
    BigDecimal maximumCostUsd,
    Duration maximumDuration) {

  public RuntimeBudget {
    Objects.requireNonNull(maximumCostUsd);
    Objects.requireNonNull(maximumDuration);
    if (maximumSteps < 1
        || maximumRetries < 0
        || maximumTokens < 1
        || maximumCostUsd.signum() < 0
        || maximumDuration.isZero()
        || maximumDuration.isNegative()) {
      throw new IllegalArgumentException("runtime budget is invalid");
    }
  }
}

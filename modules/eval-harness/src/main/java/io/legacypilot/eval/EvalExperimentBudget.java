package io.legacypilot.eval;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

public record EvalExperimentBudget(
    BigDecimal maximumCostUsd,
    Duration maximumDuration,
    int maximumTokens,
    int maximumProviderErrors,
    int concurrency) {
  public EvalExperimentBudget {
    Objects.requireNonNull(maximumCostUsd);
    Objects.requireNonNull(maximumDuration);
    if (maximumCostUsd.signum() < 0
        || maximumDuration.isZero()
        || maximumDuration.isNegative()
        || maximumTokens < 1
        || maximumProviderErrors < 0
        || concurrency < 1
        || concurrency > 32) {
      throw new IllegalArgumentException("eval experiment budget is invalid");
    }
  }
}

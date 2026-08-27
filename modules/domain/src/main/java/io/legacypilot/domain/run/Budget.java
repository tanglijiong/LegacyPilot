package io.legacypilot.domain.run;

import java.time.Duration;
import java.util.Objects;

public record Budget(int maxSteps, int maxRetries, Duration maxDuration) {

  public Budget {
    Objects.requireNonNull(maxDuration, "maxDuration must not be null");
    if (maxSteps < 1 || maxRetries < 0 || maxDuration.isZero() || maxDuration.isNegative()) {
      throw new IllegalArgumentException("budget values are outside their allowed ranges");
    }
  }
}

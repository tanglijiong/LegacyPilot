package io.legacypilot.fixtures.jobs;

import java.time.Instant;

public final class RetryPolicy {
  private static final long BASE_DELAY_SECONDS = 30;
  private static final long MAX_DELAY_SECONDS = 3600;

  public Instant nextAttempt(Instant now, int attempt) {
    if (attempt < 0) {
      throw new IllegalArgumentException("attempt must not be negative");
    }
    var exponent = Math.min(attempt, 7);
    var delay = Math.min(MAX_DELAY_SECONDS, BASE_DELAY_SECONDS << exponent);
    return now.plusSeconds(delay);
  }
}

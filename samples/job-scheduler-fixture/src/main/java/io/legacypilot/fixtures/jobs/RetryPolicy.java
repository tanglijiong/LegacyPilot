package io.legacypilot.fixtures.jobs;

import java.time.Instant;

public final class RetryPolicy {
  public Instant nextAttempt(Instant now, int attempt) {
    return now.plusSeconds(30);
  }
}

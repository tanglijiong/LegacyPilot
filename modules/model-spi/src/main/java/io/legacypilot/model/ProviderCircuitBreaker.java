package io.legacypilot.model;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class ProviderCircuitBreaker {
  private final int failureThreshold;
  private final Duration cooldown;
  private final Clock clock;
  private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

  public ProviderCircuitBreaker(int failureThreshold, Duration cooldown, Clock clock) {
    if (failureThreshold < 1 || cooldown == null || cooldown.isZero() || cooldown.isNegative()) {
      throw new IllegalArgumentException("circuit breaker configuration is invalid");
    }
    this.failureThreshold = failureThreshold;
    this.cooldown = cooldown;
    this.clock = Objects.requireNonNull(clock);
  }

  public boolean allow(String provider) {
    var state = states.get(provider);
    return state == null
        || state.openUntil() == null
        || !clock.instant().isBefore(state.openUntil());
  }

  public void success(String provider) {
    states.remove(provider);
  }

  public void failure(String provider) {
    states.compute(
        provider,
        (ignored, current) -> {
          var failures = current == null ? 1 : current.failures() + 1;
          var openUntil = failures >= failureThreshold ? clock.instant().plus(cooldown) : null;
          return new State(failures, openUntil);
        });
  }

  public Instant openUntil(String provider) {
    var state = states.get(provider);
    return state == null ? null : state.openUntil();
  }

  private record State(int failures, Instant openUntil) {}
}

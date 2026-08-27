package io.legacypilot.domain.run;

import java.time.Instant;
import java.util.Objects;

public record StatusTransition(
    int sequence, RunStatus from, RunStatus to, Instant occurredAt, String reason) {

  public StatusTransition {
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(to, "to must not be null");
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    reason = reason == null ? "" : reason;
  }
}

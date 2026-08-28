package io.legacypilot.runtime;

import java.time.Instant;
import java.util.Objects;

public record RunLease(String runId, String owner, long epoch, Instant expiresAt) {
  public RunLease {
    Objects.requireNonNull(runId);
    Objects.requireNonNull(owner);
    Objects.requireNonNull(expiresAt);
    if (runId.isBlank() || owner.isBlank() || epoch < 1) {
      throw new IllegalArgumentException("run lease is invalid");
    }
  }

  public boolean activeAt(Instant now) {
    return expiresAt.isAfter(now);
  }
}

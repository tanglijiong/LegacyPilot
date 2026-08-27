package io.legacypilot.sandbox;

import java.time.Duration;
import java.util.Objects;

public record SandboxLimits(
    double cpus,
    long memoryBytes,
    int pids,
    long temporaryStorageBytes,
    long workspaceBytes,
    Duration timeout,
    int maxOutputBytes) {

  public SandboxLimits {
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (cpus <= 0
        || memoryBytes < 64L * 1024 * 1024
        || pids < 16
        || temporaryStorageBytes < 1024 * 1024
        || workspaceBytes < 1024 * 1024
        || timeout.isZero()
        || timeout.isNegative()
        || maxOutputBytes < 1024) {
      throw new IllegalArgumentException("sandbox limits are outside their safe ranges");
    }
  }

  public static SandboxLimits safeDefaults() {
    return new SandboxLimits(
        1.0,
        1024L * 1024 * 1024,
        128,
        256L * 1024 * 1024,
        2L * 1024 * 1024 * 1024,
        Duration.ofMinutes(10),
        1024 * 1024);
  }
}

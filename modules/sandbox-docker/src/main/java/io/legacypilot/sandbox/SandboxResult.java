package io.legacypilot.sandbox;

import java.time.Duration;
import java.util.Objects;

public record SandboxResult(
    String executionId,
    SandboxStatus status,
    Integer exitCode,
    String output,
    Duration duration,
    boolean outputTruncated) {

  public SandboxResult {
    Objects.requireNonNull(executionId, "executionId must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(output, "output must not be null");
    Objects.requireNonNull(duration, "duration must not be null");
  }

  public boolean successful() {
    return status == SandboxStatus.SUCCESS;
  }
}

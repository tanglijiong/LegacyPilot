package io.legacypilot.verification;

import java.time.Duration;
import java.util.Objects;

public record VerificationEvidence(
    String name,
    VerificationStatus status,
    boolean required,
    boolean repairable,
    String command,
    Integer exitCode,
    String summary,
    String artifactReference,
    Duration duration) {

  public VerificationEvidence {
    Objects.requireNonNull(name);
    Objects.requireNonNull(status);
    command = Objects.requireNonNullElse(command, "");
    summary = Objects.requireNonNullElse(summary, "");
    artifactReference = Objects.requireNonNullElse(artifactReference, "");
    Objects.requireNonNull(duration);
    if (name.isBlank() || duration.isNegative()) {
      throw new IllegalArgumentException("verification evidence is invalid");
    }
  }
}

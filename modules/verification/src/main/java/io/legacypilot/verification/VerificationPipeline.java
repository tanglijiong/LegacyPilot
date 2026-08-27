package io.legacypilot.verification;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class VerificationPipeline {

  private final List<VerificationCheck> checks;

  public VerificationPipeline(List<VerificationCheck> checks) {
    this.checks = List.copyOf(checks);
    if (checks.isEmpty()) {
      throw new IllegalArgumentException("verification pipeline must contain checks");
    }
  }

  public VerificationOutcome verify(VerificationContext context) {
    var evidence = new ArrayList<VerificationEvidence>();
    for (var check : checks) {
      try {
        evidence.add(check.verify(context));
      } catch (RuntimeException exception) {
        evidence.add(
            new VerificationEvidence(
                check.getClass().getSimpleName(),
                VerificationStatus.FAILED,
                true,
                false,
                "",
                null,
                "Verification check failed internally",
                "",
                Duration.ZERO));
      }
    }
    return new VerificationOutcome(evidence, assess(evidence));
  }

  private static RiskLevel assess(List<VerificationEvidence> evidence) {
    if (evidence.stream().anyMatch(item -> item.status() == VerificationStatus.BLOCKED)) {
      return RiskLevel.BLOCKED;
    }
    if (evidence.stream()
        .anyMatch(item -> item.required() && item.status() == VerificationStatus.FAILED)) {
      return RiskLevel.HIGH;
    }
    if (evidence.stream().anyMatch(item -> item.status() != VerificationStatus.PASSED)) {
      return RiskLevel.MEDIUM;
    }
    return RiskLevel.LOW;
  }
}

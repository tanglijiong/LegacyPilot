package io.legacypilot.verification;

import java.util.List;
import java.util.Objects;

public record VerificationOutcome(List<VerificationEvidence> evidence, RiskLevel risk) {

  public VerificationOutcome {
    evidence = List.copyOf(Objects.requireNonNull(evidence));
    Objects.requireNonNull(risk);
  }

  public boolean successful() {
    return evidence.stream()
        .filter(VerificationEvidence::required)
        .allMatch(item -> item.status() == VerificationStatus.PASSED);
  }

  public boolean repairable() {
    return !successful()
        && evidence.stream()
            .filter(VerificationEvidence::required)
            .filter(item -> item.status() != VerificationStatus.PASSED)
            .allMatch(VerificationEvidence::repairable);
  }

  public String repairFeedback() {
    return evidence.stream()
        .filter(item -> item.status() == VerificationStatus.FAILED)
        .map(item -> item.name() + ": " + item.summary())
        .collect(java.util.stream.Collectors.joining("\n"));
  }
}

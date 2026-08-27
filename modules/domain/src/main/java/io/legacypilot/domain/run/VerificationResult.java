package io.legacypilot.domain.run;

import java.util.List;
import java.util.Objects;

public record VerificationResult(List<Check> checks) {

  public VerificationResult {
    Objects.requireNonNull(checks, "checks must not be null");
    checks = List.copyOf(checks);
  }

  public boolean successful() {
    return checks.stream()
        .filter(Check::required)
        .allMatch(check -> check.status() == Status.PASSED);
  }

  public record Check(String name, Status status, boolean required, String evidence) {
    public Check {
      Objects.requireNonNull(name, "name must not be null");
      Objects.requireNonNull(status, "status must not be null");
      evidence = evidence == null ? "" : evidence;
    }
  }

  public enum Status {
    PASSED,
    FAILED,
    SKIPPED
  }
}

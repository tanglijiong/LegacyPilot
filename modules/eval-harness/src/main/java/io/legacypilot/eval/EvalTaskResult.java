package io.legacypilot.eval;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

public record EvalTaskResult(
    String taskId,
    Status status,
    int passedAssertions,
    int totalAssertions,
    boolean compiled,
    boolean testsPassed,
    double retrievalRecall,
    int steps,
    EvalTokenUsage usage,
    BigDecimal estimatedCostUsd,
    Duration duration,
    List<String> artifacts,
    String failure) {
  public EvalTaskResult {
    Objects.requireNonNull(taskId);
    Objects.requireNonNull(status);
    Objects.requireNonNull(usage);
    Objects.requireNonNull(estimatedCostUsd);
    Objects.requireNonNull(duration);
    artifacts = List.copyOf(Objects.requireNonNull(artifacts));
    failure = Objects.requireNonNullElse(failure, "");
    if (passedAssertions < 0
        || totalAssertions < passedAssertions
        || retrievalRecall < 0
        || retrievalRecall > 1
        || steps < 0
        || estimatedCostUsd.signum() < 0
        || duration.isNegative()) {
      throw new IllegalArgumentException("eval result is invalid");
    }
  }

  public EvalTaskResult(
      String taskId,
      Status status,
      int passedAssertions,
      int totalAssertions,
      boolean compiled,
      boolean testsPassed,
      double retrievalRecall,
      int steps,
      int tokens,
      BigDecimal estimatedCostUsd,
      Duration duration,
      List<String> artifacts,
      String failure) {
    this(
        taskId,
        status,
        passedAssertions,
        totalAssertions,
        compiled,
        testsPassed,
        retrievalRecall,
        steps,
        new EvalTokenUsage(tokens, 0, 0, 0),
        estimatedCostUsd,
        duration,
        artifacts,
        failure);
  }

  public int tokens() {
    return usage.totalTokens();
  }

  public enum Status {
    PASSED,
    FAILED,
    ERROR
  }
}

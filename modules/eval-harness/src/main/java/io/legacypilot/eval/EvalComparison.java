package io.legacypilot.eval;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record EvalComparison(
    String schemaVersion,
    String datasetVersion,
    String baselineRunId,
    String candidateRunId,
    Instant generatedAt,
    Metrics baseline,
    Metrics candidate,
    List<TaskDelta> tasks,
    Map<EvalFailure, Long> candidateFailures,
    RegressionGate gate) {
  public EvalComparison {
    Objects.requireNonNull(schemaVersion);
    Objects.requireNonNull(datasetVersion);
    Objects.requireNonNull(baselineRunId);
    Objects.requireNonNull(candidateRunId);
    Objects.requireNonNull(generatedAt);
    Objects.requireNonNull(baseline);
    Objects.requireNonNull(candidate);
    tasks = List.copyOf(Objects.requireNonNull(tasks));
    candidateFailures = Map.copyOf(Objects.requireNonNull(candidateFailures));
    Objects.requireNonNull(gate);
  }

  public record Metrics(
      int tasks,
      int passed,
      double passAtOne,
      BigDecimal totalCostUsd,
      long totalTokens,
      long durationP50Millis,
      long durationP95Millis) {}

  public record TaskDelta(
      String taskId,
      EvalTaskResult.Status baselineStatus,
      EvalTaskResult.Status candidateStatus,
      EvalFailure candidateFailure,
      BigDecimal costDeltaUsd,
      long durationDeltaMillis,
      boolean regression,
      boolean improvement) {}

  public record RegressionGate(boolean passed, List<String> reasons) {
    public RegressionGate {
      reasons = List.copyOf(Objects.requireNonNull(reasons));
    }
  }
}

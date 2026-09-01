package io.legacypilot.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class EvalComparisonService {
  private final Clock clock;

  public EvalComparisonService(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
  }

  public EvalComparison compare(
      EvalExperimentManifest baselineManifest,
      EvalExperimentCheckpoint baselineCheckpoint,
      EvalExperimentManifest candidateManifest,
      EvalExperimentCheckpoint candidateCheckpoint) {
    requireComparable(baselineManifest, baselineCheckpoint, candidateManifest, candidateCheckpoint);
    var baselineResults = completed(baselineCheckpoint);
    var candidateResults = completed(candidateCheckpoint);
    var deltas = new ArrayList<EvalComparison.TaskDelta>();
    var failures = new EnumMap<EvalFailure, Long>(EvalFailure.class);
    for (var taskId : baselineManifest.taskIds()) {
      var baseline = baselineResults.get(taskId);
      var candidate = candidateResults.get(taskId);
      var failure = candidate.primaryFailure();
      if (failure != EvalFailure.NONE) {
        failures.merge(failure, 1L, Long::sum);
      }
      deltas.add(
          new EvalComparison.TaskDelta(
              taskId,
              baseline.status(),
              candidate.status(),
              failure,
              candidate.estimatedCostUsd().subtract(baseline.estimatedCostUsd()),
              candidate.duration().minus(baseline.duration()).toMillis(),
              baseline.status() == EvalTaskResult.Status.PASSED
                  && candidate.status() != EvalTaskResult.Status.PASSED,
              baseline.status() != EvalTaskResult.Status.PASSED
                  && candidate.status() == EvalTaskResult.Status.PASSED));
    }
    var baselineMetrics = metrics(baselineResults.values().stream().toList());
    var candidateMetrics = metrics(candidateResults.values().stream().toList());
    return new EvalComparison(
        "eval-comparison-v1",
        baselineManifest.datasetVersion(),
        baselineManifest.runId(),
        candidateManifest.runId(),
        clock.instant(),
        baselineMetrics,
        candidateMetrics,
        deltas,
        failures,
        gate(candidateMetrics, deltas, baselineMetrics));
  }

  private static EvalComparison.RegressionGate gate(
      EvalComparison.Metrics candidate,
      List<EvalComparison.TaskDelta> deltas,
      EvalComparison.Metrics baseline) {
    var reasons = new ArrayList<String>();
    var regressions = deltas.stream().filter(EvalComparison.TaskDelta::regression).count();
    var smokePassed =
        deltas.stream()
            .limit(5)
            .filter(value -> value.candidateStatus() == EvalTaskResult.Status.PASSED)
            .count();
    if (smokePassed < Math.min(4, Math.min(5, deltas.size()))) {
      reasons.add("candidate passes fewer than four core smoke tasks");
    }
    if (regressions > 1) {
      reasons.add("candidate regresses more than one task");
    }
    if (candidate.passAtOne() < 0.70) {
      reasons.add("candidate pass@1 is below 70%");
    }
    if (regressions == 1
        && improvement(baseline.totalCostUsd(), candidate.totalCostUsd()) < 0.15
        && improvement(baseline.durationP95Millis(), candidate.durationP95Millis()) < 0.15) {
      reasons.add("one-task regression is not offset by a 15% cost or p95 improvement");
    }
    return new EvalComparison.RegressionGate(reasons.isEmpty(), reasons);
  }

  private static double improvement(BigDecimal baseline, BigDecimal candidate) {
    if (baseline.signum() == 0) {
      return 0;
    }
    return baseline.subtract(candidate).divide(baseline, 6, RoundingMode.HALF_UP).doubleValue();
  }

  private static double improvement(long baseline, long candidate) {
    return baseline == 0 ? 0 : (double) (baseline - candidate) / baseline;
  }

  private static EvalComparison.Metrics metrics(List<EvalTaskResult> results) {
    var durations = results.stream().map(value -> value.duration().toMillis()).sorted().toList();
    var passed =
        (int)
            results.stream()
                .filter(value -> value.status() == EvalTaskResult.Status.PASSED)
                .count();
    return new EvalComparison.Metrics(
        results.size(),
        passed,
        results.isEmpty() ? 0 : (double) passed / results.size(),
        results.stream()
            .map(EvalTaskResult::estimatedCostUsd)
            .reduce(BigDecimal.ZERO, BigDecimal::add),
        results.stream().mapToLong(EvalTaskResult::tokens).sum(),
        percentile(durations, 0.50),
        percentile(durations, 0.95));
  }

  private static long percentile(List<Long> sorted, double percentile) {
    if (sorted.isEmpty()) {
      return 0;
    }
    var index = (int) Math.ceil(percentile * sorted.size()) - 1;
    return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
  }

  private static Map<String, EvalTaskResult> completed(EvalExperimentCheckpoint checkpoint) {
    var results = new LinkedHashMap<String, EvalTaskResult>();
    checkpoint
        .attempts()
        .forEach(
            (taskId, attempt) -> {
              if (attempt.status() != EvalAttemptCheckpoint.Status.COMPLETED) {
                throw new IllegalArgumentException(
                    "comparison requires every task to be completed");
              }
              results.put(taskId, attempt.result());
            });
    return results;
  }

  private static void requireComparable(
      EvalExperimentManifest baselineManifest,
      EvalExperimentCheckpoint baselineCheckpoint,
      EvalExperimentManifest candidateManifest,
      EvalExperimentCheckpoint candidateCheckpoint) {
    Objects.requireNonNull(baselineManifest);
    Objects.requireNonNull(candidateManifest);
    if (!baselineManifest.datasetVersion().equals(candidateManifest.datasetVersion())
        || !baselineManifest.datasetSha256().equals(candidateManifest.datasetSha256())
        || !baselineManifest.taskIds().equals(candidateManifest.taskIds())
        || !baselineManifest.runId().equals(baselineCheckpoint.runId())
        || !candidateManifest.runId().equals(candidateCheckpoint.runId())) {
      throw new IllegalArgumentException("eval experiments are not directly comparable");
    }
  }
}

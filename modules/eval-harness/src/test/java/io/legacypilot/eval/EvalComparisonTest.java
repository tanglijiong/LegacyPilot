package io.legacypilot.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvalComparisonTest {
  private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");

  @Test
  void classifiesFailuresComparesRunsAndEscapesDashboard() {
    var baselineManifest = manifest("baseline", "core<script>");
    var candidateManifest = manifest("candidate", "core<script>");
    var baseline = checkpoint("baseline", result("task-001", true), result("task-002", false));
    var candidate = checkpoint("candidate", result("task-001", true), result("task-002", true));

    var comparison =
        new EvalComparisonService(Clock.fixed(NOW, ZoneOffset.UTC))
            .compare(baselineManifest, baseline, candidateManifest, candidate);

    assertEquals(
        1, comparison.tasks().stream().filter(EvalComparison.TaskDelta::improvement).count());
    assertTrue(comparison.gate().passed());
    var renderer = new EvalComparisonRenderer(new ObjectMapper().findAndRegisterModules());
    assertTrue(renderer.markdown(comparison).contains("100%"));
    assertTrue(renderer.json(comparison).contains("candidateRunId"));
    assertTrue(renderer.html(comparison).contains("core&lt;script&gt;"));
  }

  @Test
  void usesDeterministicPrimaryFailurePrecedence() {
    var compileFailure =
        new EvalTaskResult(
            "task-001",
            EvalTaskResult.Status.FAILED,
            0,
            1,
            false,
            false,
            0,
            1,
            10,
            BigDecimal.ZERO,
            Duration.ZERO,
            List.of(),
            "compiler failed");
    assertEquals(EvalFailure.COMPILE_FAILURE, compileFailure.primaryFailure());

    var hiddenTestFailure =
        new EvalTaskResult(
            "task-002",
            EvalTaskResult.Status.FAILED,
            1,
            1,
            false,
            false,
            0,
            1,
            10,
            BigDecimal.ZERO,
            Duration.ZERO,
            List.of(),
            "[ERROR] Tests run: 1, Failures: 1 in Task006HiddenTest for fixture");
    assertEquals(EvalFailure.TEST_FAILURE, hiddenTestFailure.primaryFailure());

    var datasetFailure =
        new EvalTaskResult(
            "task-003",
            EvalTaskResult.Status.ERROR,
            0,
            1,
            false,
            false,
            0,
            0,
            0,
            BigDecimal.ZERO,
            Duration.ZERO,
            List.of(),
            "dataset checksum does not match");
    assertEquals(EvalFailure.DATASET_OR_INFRA_FAILURE, datasetFailure.primaryFailure());
  }

  private static EvalExperimentManifest manifest(String runId, String datasetVersion) {
    return new EvalExperimentManifest(
        "eval-experiment-v1",
        runId,
        "abcdef1",
        datasetVersion,
        "a".repeat(64),
        "model",
        "high",
        "prompt-v1",
        "b".repeat(64),
        "policy-v1",
        new EvalPricingSnapshot(
            "USD", "per-1m-tokens", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "test"),
        Map.of(),
        new EvalExperimentBudget(BigDecimal.TEN, Duration.ofHours(1), 1000, 1, 1),
        List.of("task-001", "task-002"),
        NOW);
  }

  private static EvalExperimentCheckpoint checkpoint(String runId, EvalTaskResult... results) {
    var attempts = new LinkedHashMap<String, EvalAttemptCheckpoint>();
    for (var result : results) {
      attempts.put(
          result.taskId(),
          EvalAttemptCheckpoint.pending(runId, result.taskId())
              .running(NOW)
              .completed(result, NOW));
    }
    return new EvalExperimentCheckpoint(
        runId, EvalExperimentCheckpoint.Status.COMPLETED, attempts, NOW);
  }

  private static EvalTaskResult result(String taskId, boolean passed) {
    return new EvalTaskResult(
        taskId,
        passed ? EvalTaskResult.Status.PASSED : EvalTaskResult.Status.FAILED,
        passed ? 1 : 0,
        1,
        true,
        passed,
        1,
        1,
        10,
        BigDecimal.ZERO,
        Duration.ofSeconds(1),
        List.of(),
        passed ? "" : "tests failed");
  }
}

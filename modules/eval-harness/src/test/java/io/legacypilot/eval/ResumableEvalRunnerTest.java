package io.legacypilot.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ResumableEvalRunnerTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC);

  @TempDir Path directory;

  @Test
  void resumesPendingTasksWithoutRepeatingCompletedProviderCalls() {
    var tasks = tasks(2);
    var store = store("skip-completed");
    var calls = new HashMap<String, Integer>();
    EvalTaskExecutor executor = task -> recordCall(calls, task, new BigDecimal("0.01"));
    var runner =
        new ResumableEvalRunner(
            CLOCK,
            store,
            (point, taskId) -> {
              if (point == EvalFaultInjector.Point.BEFORE_TASK_START && taskId.equals("task-002")) {
                throw new SimulatedExit();
              }
            });

    assertThrows(SimulatedExit.class, () -> runner.start(manifest(tasks), tasks, executor));
    var resumed = new ResumableEvalRunner(CLOCK, store).resume(tasks, executor);

    assertEquals(EvalExperimentCheckpoint.Status.COMPLETED, resumed.status());
    assertEquals(1, calls.get("task-001"));
    assertEquals(1, calls.get("task-002"));
  }

  @ParameterizedTest
  @EnumSource(
      value = EvalFaultInjector.Point.class,
      names = {"AFTER_MODEL_RESPONSE", "AFTER_PATCH_APPLIED", "BEFORE_RESULT_PERSIST"})
  void uncertainProviderOutcomeNeedsReviewAndIsNeverReplayed(EvalFaultInjector.Point exitPoint) {
    var tasks = tasks(2);
    var store = store("uncertain");
    var calls = new HashMap<String, Integer>();
    EvalTaskExecutor executor = task -> recordCall(calls, task, new BigDecimal("0.01"));
    var runner =
        new ResumableEvalRunner(
            CLOCK,
            store,
            (point, taskId) -> {
              if (point == exitPoint && taskId.equals("task-001")) {
                throw new SimulatedExit();
              }
            });

    assertThrows(SimulatedExit.class, () -> runner.start(manifest(tasks), tasks, executor));
    var resumed = new ResumableEvalRunner(CLOCK, store).resume(tasks, executor);

    assertEquals(EvalExperimentCheckpoint.Status.NEEDS_REVIEW, resumed.status());
    assertEquals(
        EvalAttemptCheckpoint.Status.NEEDS_REVIEW, resumed.attempts().get("task-001").status());
    assertEquals(
        EvalAttemptCheckpoint.Status.COMPLETED, resumed.attempts().get("task-002").status());
    assertEquals(1, calls.get("task-001"));
    assertEquals(1, calls.get("task-002"));
  }

  @Test
  void honorsTheManifestConcurrencyForIndependentTasks() {
    var tasks = tasks(2);
    var store = store("concurrency");
    var active = new AtomicInteger();
    var maximumActive = new AtomicInteger();
    var barrier = new CyclicBarrier(2);
    EvalTaskExecutor executor =
        task -> {
          var current = active.incrementAndGet();
          maximumActive.accumulateAndGet(current, Math::max);
          try {
            barrier.await(2, TimeUnit.SECONDS);
            return passed(task, BigDecimal.ZERO);
          } catch (Exception exception) {
            throw new IllegalStateException(exception);
          } finally {
            active.decrementAndGet();
          }
        };
    var budget =
        new EvalExperimentBudget(new BigDecimal("10.00"), Duration.ofHours(1), 1_000_000, 3, 2);

    var result =
        new ResumableEvalRunner(CLOCK, store)
            .start(manifest(tasks, budget, Map.of()), tasks, executor);

    assertEquals(EvalExperimentCheckpoint.Status.COMPLETED, result.status());
    assertEquals(2, maximumActive.get());
  }

  @Test
  void stopsBeforeStartingAnotherTaskWhenTheGlobalBudgetIsExhausted() {
    var tasks = tasks(2);
    var store = store("budget");
    var calls = new HashMap<String, Integer>();
    EvalTaskExecutor executor = task -> recordCall(calls, task, new BigDecimal("0.10"));
    var budget =
        new EvalExperimentBudget(new BigDecimal("0.10"), Duration.ofHours(1), 10_000, 1, 1);

    var result =
        new ResumableEvalRunner(CLOCK, store)
            .start(manifest(tasks, budget, Map.of()), tasks, executor);

    assertEquals(EvalExperimentCheckpoint.Status.BUDGET_EXHAUSTED, result.status());
    assertEquals(1, calls.get("task-001"));
    assertEquals(null, calls.get("task-002"));
    assertEquals(EvalAttemptCheckpoint.Status.PENDING, result.attempts().get("task-002").status());
  }

  @Test
  void enforcesTokenAndProviderErrorBudgetsBetweenConcurrentBatches() {
    var tasks = tasks(2);
    var tokenCalls = new HashMap<String, Integer>();
    var tokenBudget =
        new EvalExperimentBudget(new BigDecimal("10.00"), Duration.ofHours(1), 100, 3, 1);
    EvalTaskExecutor tokenExecutor =
        task -> {
          tokenCalls.merge(task.id(), 1, Integer::sum);
          return passed(task, BigDecimal.ZERO);
        };

    var tokenResult =
        new ResumableEvalRunner(CLOCK, store("token-budget"))
            .start(manifest(tasks, tokenBudget, Map.of()), tasks, tokenExecutor);

    assertEquals(EvalExperimentCheckpoint.Status.BUDGET_EXHAUSTED, tokenResult.status());
    assertEquals(1, tokenCalls.size());

    var errorCalls = new HashMap<String, Integer>();
    var errorBudget =
        new EvalExperimentBudget(new BigDecimal("10.00"), Duration.ofHours(1), 10_000, 1, 1);
    EvalTaskExecutor errorExecutor =
        task -> {
          errorCalls.merge(task.id(), 1, Integer::sum);
          return failedInternally(task);
        };

    var errorResult =
        new ResumableEvalRunner(CLOCK, store("error-budget"))
            .start(manifest(tasks, errorBudget, Map.of()), tasks, errorExecutor);

    assertEquals(EvalExperimentCheckpoint.Status.BUDGET_EXHAUSTED, errorResult.status());
    assertEquals(1, errorCalls.size());
  }

  @Test
  void keepsTheManifestImmutableAndRejectsCredentialMetadata() {
    var tasks = tasks(1);
    var store = store("immutable");
    var manifest = manifest(tasks);
    new ResumableEvalRunner(CLOCK, store)
        .start(manifest, tasks, task -> passed(task, BigDecimal.ZERO));

    assertThrows(
        IllegalStateException.class,
        () ->
            new ResumableEvalRunner(CLOCK, store)
                .start(manifest, tasks, task -> passed(task, BigDecimal.ZERO)));
    assertThrows(
        IllegalArgumentException.class,
        () -> manifest(tasks, manifest.budget(), Map.of("api_key", "must-not-persist")));
  }

  private EvalExperimentStore store(String name) {
    return new EvalExperimentStore(
        directory.resolve(name), new ObjectMapper().findAndRegisterModules());
  }

  private static EvalExperimentManifest manifest(List<EvalTask> tasks) {
    return manifest(
        tasks,
        new EvalExperimentBudget(new BigDecimal("10.00"), Duration.ofHours(1), 1_000_000, 3, 1),
        Map.of("java", "21", "os", "test"));
  }

  private static EvalExperimentManifest manifest(
      List<EvalTask> tasks, EvalExperimentBudget budget, Map<String, String> environment) {
    return new EvalExperimentManifest(
        "eval-experiment-v1",
        "run-test",
        "0123456789abcdef0123456789abcdef01234567",
        "v0.3-core.1",
        "a".repeat(64),
        "fake-model",
        "high",
        "prompt-v1",
        "b".repeat(64),
        "policy-v1",
        new EvalPricingSnapshot(
            "USD",
            "per-1m-tokens",
            new BigDecimal("1.00"),
            new BigDecimal("0.10"),
            new BigDecimal("5.00"),
            "test-price"),
        environment,
        budget,
        tasks.stream().map(EvalTask::id).toList(),
        CLOCK.instant());
  }

  private static EvalTaskResult recordCall(
      Map<String, Integer> calls, EvalTask task, BigDecimal cost) {
    calls.merge(task.id(), 1, Integer::sum);
    return passed(task, cost);
  }

  private static EvalTaskResult passed(EvalTask task, BigDecimal cost) {
    return new EvalTaskResult(
        task.id(),
        EvalTaskResult.Status.PASSED,
        task.assertions().size(),
        task.assertions().size(),
        true,
        true,
        1,
        1,
        100,
        cost,
        Duration.ofSeconds(1),
        task.expectedFiles(),
        "");
  }

  private static EvalTaskResult failedInternally(EvalTask task) {
    return new EvalTaskResult(
        task.id(),
        EvalTaskResult.Status.ERROR,
        0,
        task.assertions().size(),
        false,
        false,
        0,
        1,
        EvalTokenUsage.NONE,
        BigDecimal.ZERO,
        Duration.ofSeconds(1),
        List.of(),
        "provider unavailable");
  }

  private static List<EvalTask> tasks(int count) {
    return new EvalDatasetLoader()
        .load(Path.of("../../evals/datasets/v0.1").toAbsolutePath().normalize())
        .subList(0, count);
  }

  private static final class SimulatedExit extends RuntimeException {}
}

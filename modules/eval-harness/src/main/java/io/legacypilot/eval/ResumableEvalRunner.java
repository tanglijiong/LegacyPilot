package io.legacypilot.eval;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ResumableEvalRunner {
  private final Clock clock;
  private final EvalExperimentStore store;
  private final EvalFaultInjector faults;

  public ResumableEvalRunner(Clock clock, EvalExperimentStore store) {
    this(clock, store, EvalFaultInjector.NONE);
  }

  public ResumableEvalRunner(Clock clock, EvalExperimentStore store, EvalFaultInjector faults) {
    this.clock = Objects.requireNonNull(clock);
    this.store = Objects.requireNonNull(store);
    this.faults = Objects.requireNonNull(faults);
  }

  public EvalExperimentCheckpoint start(
      EvalExperimentManifest manifest, List<EvalTask> tasks, EvalTaskExecutor executor) {
    validateTasks(manifest, tasks);
    store.createManifest(manifest);
    var attempts = new LinkedHashMap<String, EvalAttemptCheckpoint>();
    tasks.forEach(
        task ->
            attempts.put(task.id(), EvalAttemptCheckpoint.pending(manifest.runId(), task.id())));
    var initial =
        new EvalExperimentCheckpoint(
            manifest.runId(), EvalExperimentCheckpoint.Status.RUNNING, attempts, clock.instant());
    store.save(initial);
    return run(manifest, tasks, executor, initial);
  }

  public EvalExperimentCheckpoint resume(List<EvalTask> tasks, EvalTaskExecutor executor) {
    var manifest = store.manifest();
    validateTasks(manifest, tasks);
    var checkpoint =
        store
            .load()
            .orElseThrow(() -> new IllegalStateException("eval experiment checkpoint is missing"));
    if (!checkpoint.runId().equals(manifest.runId())) {
      throw new IllegalStateException("eval experiment state does not match its manifest");
    }
    if (checkpoint.status() == EvalExperimentCheckpoint.Status.COMPLETED
        || checkpoint.status() == EvalExperimentCheckpoint.Status.BUDGET_EXHAUSTED) {
      return checkpoint;
    }
    var recovered = new LinkedHashMap<>(checkpoint.attempts());
    var changed = false;
    for (var entry : recovered.entrySet()) {
      if (entry.getValue().status() == EvalAttemptCheckpoint.Status.RUNNING) {
        entry.setValue(entry.getValue().needsReview());
        changed = true;
      }
    }
    if (changed) {
      checkpoint =
          new EvalExperimentCheckpoint(
              checkpoint.runId(),
              EvalExperimentCheckpoint.Status.NEEDS_REVIEW,
              recovered,
              clock.instant());
      store.save(checkpoint);
    }
    return run(manifest, tasks, executor, checkpoint);
  }

  private EvalExperimentCheckpoint run(
      EvalExperimentManifest manifest,
      List<EvalTask> tasks,
      EvalTaskExecutor executor,
      EvalExperimentCheckpoint checkpoint) {
    Objects.requireNonNull(executor);
    var attempts = new LinkedHashMap<>(checkpoint.attempts());
    var pending =
        tasks.stream()
            .filter(
                task -> attempts.get(task.id()).status() == EvalAttemptCheckpoint.Status.PENDING)
            .toList();
    try (var pool =
        java.util.concurrent.Executors.newFixedThreadPool(
            manifest.budget().concurrency(), Thread.ofVirtual().factory())) {
      for (var offset = 0; offset < pending.size(); offset += manifest.budget().concurrency()) {
        if (exhausted(manifest.budget(), attempts)) {
          return save(manifest.runId(), EvalExperimentCheckpoint.Status.BUDGET_EXHAUSTED, attempts);
        }
        var batch =
            pending.subList(
                offset, Math.min(pending.size(), offset + manifest.budget().concurrency()));
        for (var task : batch) {
          faults.reach(EvalFaultInjector.Point.BEFORE_TASK_START, task.id());
        }
        for (var task : batch) {
          attempts.put(task.id(), attempts.get(task.id()).running(clock.instant()));
          save(manifest.runId(), EvalExperimentCheckpoint.Status.RUNNING, attempts);
        }
        var futures =
            batch.stream().map(task -> pool.submit(() -> execute(task, executor))).toList();
        for (var index = 0; index < batch.size(); index++) {
          var task = batch.get(index);
          EvalTaskResult result;
          try {
            result = futures.get(index).get();
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("eval experiment was interrupted", exception);
          } catch (java.util.concurrent.ExecutionException exception) {
            throw new IllegalStateException("eval task result collection failed", exception);
          }
          faults.reach(EvalFaultInjector.Point.AFTER_MODEL_RESPONSE, task.id());
          faults.reach(EvalFaultInjector.Point.AFTER_PATCH_APPLIED, task.id());
          faults.reach(EvalFaultInjector.Point.BEFORE_RESULT_PERSIST, task.id());
          attempts.put(task.id(), attempts.get(task.id()).completed(result, clock.instant()));
          save(manifest.runId(), EvalExperimentCheckpoint.Status.RUNNING, attempts);
        }
      }
    }
    var status =
        attempts.values().stream()
                .anyMatch(value -> value.status() == EvalAttemptCheckpoint.Status.NEEDS_REVIEW)
            ? EvalExperimentCheckpoint.Status.NEEDS_REVIEW
            : EvalExperimentCheckpoint.Status.COMPLETED;
    return save(manifest.runId(), status, attempts);
  }

  private EvalExperimentCheckpoint save(
      String runId,
      EvalExperimentCheckpoint.Status status,
      Map<String, EvalAttemptCheckpoint> attempts) {
    var value = new EvalExperimentCheckpoint(runId, status, attempts, clock.instant());
    store.save(value);
    return value;
  }

  private static EvalTaskResult execute(EvalTask task, EvalTaskExecutor executor) {
    try {
      return executor.execute(task);
    } catch (RuntimeException exception) {
      return new EvalTaskResult(
          task.id(),
          EvalTaskResult.Status.ERROR,
          0,
          task.assertions().size(),
          false,
          false,
          0,
          0,
          0,
          BigDecimal.ZERO,
          Duration.ZERO,
          List.of(),
          "Task execution failed internally");
    }
  }

  private static boolean exhausted(
      EvalExperimentBudget budget, Map<String, EvalAttemptCheckpoint> attempts) {
    var results =
        attempts.values().stream()
            .filter(value -> value.status() == EvalAttemptCheckpoint.Status.COMPLETED)
            .map(EvalAttemptCheckpoint::result)
            .toList();
    var cost =
        results.stream()
            .map(EvalTaskResult::estimatedCostUsd)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    var duration =
        results.stream().map(EvalTaskResult::duration).reduce(Duration.ZERO, Duration::plus);
    var providerErrors =
        results.stream().filter(value -> value.status() == EvalTaskResult.Status.ERROR).count();
    var tokens = results.stream().mapToInt(EvalTaskResult::tokens).sum();
    return cost.compareTo(budget.maximumCostUsd()) >= 0
        || duration.compareTo(budget.maximumDuration()) >= 0
        || tokens >= budget.maximumTokens()
        || (providerErrors > 0 && providerErrors >= budget.maximumProviderErrors());
  }

  private static void validateTasks(EvalExperimentManifest manifest, List<EvalTask> tasks) {
    Objects.requireNonNull(manifest);
    Objects.requireNonNull(tasks);
    var taskIds = tasks.stream().map(EvalTask::id).toList();
    if (!manifest.taskIds().equals(taskIds)) {
      throw new IllegalArgumentException("eval tasks do not match the immutable manifest");
    }
  }
}

package io.legacypilot.eval;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;

public final class EvalRunner {
  private final Clock clock;

  public EvalRunner(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
  }

  public EvalSummary run(
      String datasetVersion,
      String model,
      String promptVersion,
      String policyVersion,
      Map<String, String> environment,
      List<EvalTask> tasks,
      int concurrency,
      EvalTaskExecutor taskExecutor) {
    if (tasks.isEmpty() || concurrency < 1 || concurrency > 32) {
      throw new IllegalArgumentException("eval execution settings are invalid");
    }
    var started = clock.instant();
    var results = new ArrayList<EvalTaskResult>();
    try (var executor = Executors.newFixedThreadPool(concurrency, Thread.ofVirtual().factory())) {
      var futures =
          tasks.stream().map(task -> executor.submit(() -> execute(task, taskExecutor))).toList();
      for (var future : futures) {
        try {
          results.add(future.get());
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("eval interrupted", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
          throw new IllegalStateException("eval result collection failed", exception);
        }
      }
    }
    return new EvalSummary(
        datasetVersion,
        model,
        promptVersion,
        policyVersion,
        started,
        clock.instant(),
        environment,
        results);
  }

  private EvalTaskResult execute(EvalTask task, EvalTaskExecutor executor) {
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
}

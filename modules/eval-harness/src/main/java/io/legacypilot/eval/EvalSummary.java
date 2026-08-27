package io.legacypilot.eval;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record EvalSummary(
    String datasetVersion,
    String model,
    String promptVersion,
    String policyVersion,
    Instant startedAt,
    Instant completedAt,
    Map<String, String> environment,
    List<EvalTaskResult> results) {
  public EvalSummary {
    Objects.requireNonNull(datasetVersion);
    Objects.requireNonNull(model);
    Objects.requireNonNull(promptVersion);
    Objects.requireNonNull(policyVersion);
    Objects.requireNonNull(startedAt);
    Objects.requireNonNull(completedAt);
    environment = Map.copyOf(Objects.requireNonNull(environment));
    results = List.copyOf(Objects.requireNonNull(results));
  }

  public double successRate() {
    return results.isEmpty()
        ? 0
        : (double)
                results.stream()
                    .filter(value -> value.status() == EvalTaskResult.Status.PASSED)
                    .count()
            / results.size();
  }

  public double averageRecall() {
    return results.stream().mapToDouble(EvalTaskResult::retrievalRecall).average().orElse(0);
  }
}

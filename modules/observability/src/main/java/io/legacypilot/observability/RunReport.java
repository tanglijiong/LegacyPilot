package io.legacypilot.observability;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RunReport(
    String runId,
    String status,
    String summary,
    int steps,
    int modelTokens,
    BigDecimal estimatedCostUsd,
    Duration duration,
    String risk,
    List<String> plan,
    List<Map<String, String>> verification,
    List<TraceEvent> trace) {

  public RunReport {
    Objects.requireNonNull(runId);
    Objects.requireNonNull(status);
    Objects.requireNonNull(summary);
    Objects.requireNonNull(estimatedCostUsd);
    Objects.requireNonNull(duration);
    Objects.requireNonNull(risk);
    plan = List.copyOf(plan);
    verification = verification.stream().map(Map::copyOf).toList();
    trace = List.copyOf(trace);
  }

  @Override
  public List<Map<String, String>> verification() {
    return verification.stream().map(Map::copyOf).toList();
  }
}

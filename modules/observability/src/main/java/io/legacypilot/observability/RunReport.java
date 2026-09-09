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
    List<TraceEvent> trace,
    String taskMode,
    TaskArtifact artifact,
    Map<String, String> groundingMetrics) {

  public RunReport {
    Objects.requireNonNull(runId);
    Objects.requireNonNull(status);
    Objects.requireNonNull(summary);
    Objects.requireNonNull(estimatedCostUsd);
    Objects.requireNonNull(duration);
    Objects.requireNonNull(risk);
    taskMode = Objects.requireNonNullElse(taskMode, "CHANGE");
    groundingMetrics = Map.copyOf(Objects.requireNonNullElse(groundingMetrics, Map.of()));
    plan = List.copyOf(plan);
    verification = verification.stream().map(Map::copyOf).toList();
    trace = List.copyOf(trace);
  }

  public RunReport(
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
    this(
        runId,
        status,
        summary,
        steps,
        modelTokens,
        estimatedCostUsd,
        duration,
        risk,
        plan,
        verification,
        trace,
        "CHANGE",
        null,
        Map.of());
  }

  @Override
  public List<Map<String, String>> verification() {
    return verification.stream().map(Map::copyOf).toList();
  }

  @Override
  public Map<String, String> groundingMetrics() {
    return Map.copyOf(groundingMetrics);
  }
}

package io.legacypilot.model;

import java.time.Duration;
import java.time.Instant;

public record ModelRouteEvent(
    String profileId,
    String provider,
    String model,
    int attempt,
    Outcome outcome,
    ModelErrorType errorType,
    int totalTokens,
    java.math.BigDecimal estimatedCostUsd,
    Duration duration,
    Instant recordedAt) {
  public enum Outcome {
    SUCCEEDED,
    FAILED,
    CIRCUIT_OPEN,
    BUDGET_EXHAUSTED
  }
}

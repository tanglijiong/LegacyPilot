package io.legacypilot.model;

import java.math.BigDecimal;
import java.util.Objects;

public record ModelUsage(
    int inputTokens, int outputTokens, BigDecimal estimatedCostUsd, String model) {

  public static final ModelUsage NONE = new ModelUsage(0, 0, BigDecimal.ZERO, "unknown");

  public ModelUsage {
    Objects.requireNonNull(estimatedCostUsd, "estimatedCostUsd must not be null");
    Objects.requireNonNull(model, "model must not be null");
    if (inputTokens < 0 || outputTokens < 0 || estimatedCostUsd.signum() < 0 || model.isBlank()) {
      throw new IllegalArgumentException("model usage is invalid");
    }
  }

  public int totalTokens() {
    return inputTokens + outputTokens;
  }

  public ModelUsage plus(ModelUsage other) {
    Objects.requireNonNull(other);
    return new ModelUsage(
        inputTokens + other.inputTokens,
        outputTokens + other.outputTokens,
        estimatedCostUsd.add(other.estimatedCostUsd),
        model.equals("unknown") ? other.model : model);
  }
}

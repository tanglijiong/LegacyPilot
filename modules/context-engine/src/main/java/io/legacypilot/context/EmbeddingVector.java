package io.legacypilot.context;

import java.util.List;
import java.util.Objects;

public record EmbeddingVector(String model, List<Double> values) {
  public EmbeddingVector {
    Objects.requireNonNull(model);
    values = values == null ? List.of() : List.copyOf(values);
    if (model.isBlank()
        || values.isEmpty()
        || values.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
      throw new IllegalArgumentException("embedding vector is invalid");
    }
  }
}

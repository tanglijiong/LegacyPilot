package io.legacypilot.context;

import java.util.Objects;

public record WeightedRetriever(Retriever retriever, double weight) {

  public WeightedRetriever {
    Objects.requireNonNull(retriever, "retriever must not be null");
    if (weight <= 0 || !Double.isFinite(weight)) {
      throw new IllegalArgumentException("retriever weight must be finite and positive");
    }
  }
}

package io.legacypilot.analysis.java;

import java.util.Objects;

public record GraphHit(SourceSymbol symbol, DependencyEdge edge, int depth) {

  public GraphHit {
    Objects.requireNonNull(symbol, "symbol must not be null");
    Objects.requireNonNull(edge, "edge must not be null");
    if (depth < 1) {
      throw new IllegalArgumentException("graph depth must be positive");
    }
  }
}

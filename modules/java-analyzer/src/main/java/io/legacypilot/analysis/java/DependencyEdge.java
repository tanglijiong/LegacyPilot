package io.legacypilot.analysis.java;

import java.util.Objects;

public record DependencyEdge(
    String fromSymbolId,
    String toSymbolId,
    String targetName,
    DependencyKind kind,
    String path,
    SourceRange evidence,
    double confidence) {

  public DependencyEdge {
    Objects.requireNonNull(fromSymbolId, "fromSymbolId must not be null");
    Objects.requireNonNull(targetName, "targetName must not be null");
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(path, "path must not be null");
    Objects.requireNonNull(evidence, "evidence must not be null");
    if (fromSymbolId.isBlank() || targetName.isBlank() || confidence < 0 || confidence > 1) {
      throw new IllegalArgumentException("dependency edge is invalid");
    }
  }

  public boolean resolved() {
    return toSymbolId != null;
  }
}

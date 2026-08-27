package io.legacypilot.context;

import java.util.Objects;

public record ContextRequest(String query, int tokenBudget, int maximumCandidates, int graphDepth) {

  public ContextRequest {
    Objects.requireNonNull(query, "query must not be null");
    if (query.isBlank()
        || tokenBudget < 1
        || maximumCandidates < 1
        || maximumCandidates > 10_000
        || graphDepth < 0
        || graphDepth > 10) {
      throw new IllegalArgumentException("context request is invalid");
    }
  }
}

package io.legacypilot.context;

import java.util.List;
import java.util.Objects;

public record ContextBuildResult(
    List<ContextChunk> chunks, List<ContextDecision> omitted, int usedTokens, int tokenBudget) {

  public ContextBuildResult {
    Objects.requireNonNull(chunks, "chunks must not be null");
    Objects.requireNonNull(omitted, "omitted must not be null");
    if (usedTokens < 0 || tokenBudget < 1 || usedTokens > tokenBudget) {
      throw new IllegalArgumentException("context token accounting is invalid");
    }
    chunks = List.copyOf(chunks);
    omitted = List.copyOf(omitted);
  }
}

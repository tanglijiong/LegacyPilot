package io.legacypilot.context;

import java.util.List;

public record CompactedContext(
    int version,
    String content,
    int estimatedTokens,
    List<String> retainedMemoryIds,
    List<ContextDecision> decisions) {
  public CompactedContext {
    retainedMemoryIds = List.copyOf(retainedMemoryIds);
    decisions = List.copyOf(decisions);
  }
}

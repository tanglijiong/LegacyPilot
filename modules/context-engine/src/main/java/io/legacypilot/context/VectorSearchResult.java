package io.legacypilot.context;

import java.util.List;
import java.util.Objects;

public record VectorSearchResult(
    List<EvidenceCandidate> candidates, boolean degraded, String reason) {
  public VectorSearchResult {
    candidates = candidates == null ? List.of() : List.copyOf(candidates);
    reason = Objects.requireNonNullElse(reason, "");
    if (degraded && reason.isBlank()) {
      throw new IllegalArgumentException("degraded vector search requires a reason");
    }
  }
}

package io.legacypilot.context;

import io.legacypilot.analysis.java.SourceRange;
import java.util.Objects;
import java.util.Set;

public record EvidenceCandidate(
    String referenceId,
    String symbolId,
    String path,
    SourceRange range,
    String summary,
    double score,
    Set<RetrievalSource> sources,
    String reason) {

  public EvidenceCandidate {
    Objects.requireNonNull(referenceId, "referenceId must not be null");
    Objects.requireNonNull(symbolId, "symbolId must not be null");
    Objects.requireNonNull(path, "path must not be null");
    Objects.requireNonNull(range, "range must not be null");
    Objects.requireNonNull(summary, "summary must not be null");
    Objects.requireNonNull(sources, "sources must not be null");
    Objects.requireNonNull(reason, "reason must not be null");
    if (referenceId.isBlank() || symbolId.isBlank() || path.isBlank() || score < 0) {
      throw new IllegalArgumentException("retrieval candidate is invalid");
    }
    sources = Set.copyOf(sources);
  }
}

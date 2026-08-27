package io.legacypilot.context;

import io.legacypilot.analysis.java.SourceRange;
import java.util.Objects;
import java.util.Set;

public record ContextChunk(
    String referenceId,
    String symbolId,
    String path,
    SourceRange range,
    String content,
    int tokens,
    double score,
    Set<RetrievalSource> sources,
    String reason) {

  public ContextChunk {
    Objects.requireNonNull(referenceId, "referenceId must not be null");
    Objects.requireNonNull(symbolId, "symbolId must not be null");
    Objects.requireNonNull(path, "path must not be null");
    Objects.requireNonNull(range, "range must not be null");
    Objects.requireNonNull(content, "content must not be null");
    Objects.requireNonNull(sources, "sources must not be null");
    Objects.requireNonNull(reason, "reason must not be null");
    if (tokens < 1 || score < 0) {
      throw new IllegalArgumentException("context chunk is invalid");
    }
    sources = Set.copyOf(sources);
  }
}

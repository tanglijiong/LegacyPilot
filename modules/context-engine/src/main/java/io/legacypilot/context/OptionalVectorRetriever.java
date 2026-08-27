package io.legacypilot.context;

import io.legacypilot.analysis.java.ProjectIndex;
import java.util.List;
import java.util.Optional;

public final class OptionalVectorRetriever implements Retriever {

  private final Optional<Retriever> delegate;

  public OptionalVectorRetriever(Optional<Retriever> delegate) {
    this.delegate = delegate;
  }

  public static OptionalVectorRetriever disabled() {
    return new OptionalVectorRetriever(Optional.empty());
  }

  @Override
  public List<EvidenceCandidate> retrieve(ProjectIndex index, String query, int limit) {
    if (delegate.isEmpty()) {
      return List.of();
    }
    try {
      return delegate.get().retrieve(index, query, limit).stream()
          .map(
              candidate ->
                  new EvidenceCandidate(
                      candidate.referenceId(),
                      candidate.symbolId(),
                      candidate.path(),
                      candidate.range(),
                      candidate.summary(),
                      candidate.score(),
                      java.util.Set.of(RetrievalSource.VECTOR),
                      candidate.reason()))
          .toList();
    } catch (RuntimeException exception) {
      return List.of();
    }
  }
}

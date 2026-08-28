package io.legacypilot.context;

import io.legacypilot.analysis.java.ProjectIndex;
import java.util.List;
import java.util.Objects;

public final class RerankingRetriever implements Retriever {
  private final Retriever candidates;
  private final Reranker reranker;
  private final int candidateMultiplier;

  public RerankingRetriever(Retriever candidates, Reranker reranker, int candidateMultiplier) {
    this.candidates = Objects.requireNonNull(candidates);
    this.reranker = Objects.requireNonNull(reranker);
    if (candidateMultiplier < 1 || candidateMultiplier > 20) {
      throw new IllegalArgumentException("reranker candidate multiplier is invalid");
    }
    this.candidateMultiplier = candidateMultiplier;
  }

  @Override
  public List<EvidenceCandidate> retrieve(ProjectIndex index, String query, int limit) {
    return reranker.rerank(
        query, candidates.retrieve(index, query, limit * candidateMultiplier), limit);
  }
}

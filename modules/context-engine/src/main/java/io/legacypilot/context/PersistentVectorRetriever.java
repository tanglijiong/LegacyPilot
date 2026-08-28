package io.legacypilot.context;

import io.legacypilot.analysis.java.ProjectIndex;
import java.util.List;
import java.util.Objects;

public final class PersistentVectorRetriever implements Retriever {
  private final EmbeddingProvider embeddings;
  private final VectorStore store;

  public PersistentVectorRetriever(EmbeddingProvider embeddings, VectorStore store) {
    this.embeddings = Objects.requireNonNull(embeddings);
    this.store = Objects.requireNonNull(store);
  }

  @Override
  public List<EvidenceCandidate> retrieve(ProjectIndex index, String query, int limit) {
    return retrieveWithStatus(index, query, limit).candidates();
  }

  public VectorSearchResult retrieveWithStatus(ProjectIndex index, String query, int limit) {
    ExactSymbolRetriever.validate(query, limit);
    try {
      var vector = embeddings.embed(query);
      var candidates =
          store.search(index.revision(), vector, limit).stream()
              .map(
                  match ->
                      new EvidenceCandidate(
                          CandidateSupport.referenceId(match.entry().symbolId()),
                          match.entry().symbolId(),
                          match.entry().path(),
                          match.entry().range(),
                          match.entry().summary(),
                          match.score(),
                          java.util.Set.of(RetrievalSource.VECTOR),
                          "vector cosine match using " + vector.model()))
              .toList();
      return new VectorSearchResult(candidates, false, "");
    } catch (RuntimeException exception) {
      return new VectorSearchResult(
          List.of(),
          true,
          "vector provider unavailable; lexical and graph retrieval remain active");
    }
  }
}

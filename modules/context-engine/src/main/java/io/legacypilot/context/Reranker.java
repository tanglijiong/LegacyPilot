package io.legacypilot.context;

import java.util.List;

@FunctionalInterface
public interface Reranker {
  List<EvidenceCandidate> rerank(String query, List<EvidenceCandidate> candidates, int limit);
}

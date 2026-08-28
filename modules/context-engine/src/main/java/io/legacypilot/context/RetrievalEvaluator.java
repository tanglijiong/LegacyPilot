package io.legacypilot.context;

import java.util.List;
import java.util.Set;

public final class RetrievalEvaluator {

  private RetrievalEvaluator() {}

  public static double recallAtK(
      List<EvidenceCandidate> candidates, Set<String> relevantSymbolIds, int k) {
    if (relevantSymbolIds.isEmpty() || k < 1) {
      throw new IllegalArgumentException("relevant ids and k must be provided");
    }
    var found =
        candidates.stream()
            .limit(k)
            .map(EvidenceCandidate::symbolId)
            .filter(relevantSymbolIds::contains)
            .distinct()
            .count();
    return (double) found / relevantSymbolIds.size();
  }

  public static double reciprocalRank(
      List<EvidenceCandidate> candidates, Set<String> relevantSymbolIds) {
    if (relevantSymbolIds.isEmpty()) {
      throw new IllegalArgumentException("relevant ids must be provided");
    }
    for (var index = 0; index < candidates.size(); index++) {
      if (relevantSymbolIds.contains(candidates.get(index).symbolId())) {
        return 1.0 / (index + 1);
      }
    }
    return 0;
  }
}

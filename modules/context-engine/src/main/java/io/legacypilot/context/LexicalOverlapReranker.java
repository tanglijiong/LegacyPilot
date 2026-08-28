package io.legacypilot.context;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public final class LexicalOverlapReranker implements Reranker {
  @Override
  public List<EvidenceCandidate> rerank(
      String query, List<EvidenceCandidate> candidates, int limit) {
    if (query == null || query.isBlank() || limit < 1) {
      throw new IllegalArgumentException("reranker query and limit are required");
    }
    var terms = terms(query);
    return candidates.stream()
        .map(candidate -> new Scored(candidate, overlap(terms, terms(candidate.summary()))))
        .sorted(
            Comparator.comparingDouble(Scored::overlap)
                .reversed()
                .thenComparing(
                    Comparator.comparingDouble((Scored scored) -> scored.candidate().score())
                        .reversed())
                .thenComparing(scored -> scored.candidate().symbolId()))
        .limit(limit)
        .map(Scored::candidate)
        .toList();
  }

  private static java.util.Set<String> terms(String value) {
    var result = new HashSet<String>();
    for (var term : value.toLowerCase(Locale.ROOT).split("[^a-z0-9_$]+")) {
      if (!term.isBlank()) {
        result.add(term);
      }
    }
    return result;
  }

  private static double overlap(java.util.Set<String> left, java.util.Set<String> right) {
    return left.stream().filter(right::contains).count();
  }

  private record Scored(EvidenceCandidate candidate, double overlap) {}
}

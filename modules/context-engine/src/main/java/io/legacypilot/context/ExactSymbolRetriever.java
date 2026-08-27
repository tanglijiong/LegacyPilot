package io.legacypilot.context;

import io.legacypilot.analysis.java.ProjectIndex;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ExactSymbolRetriever implements Retriever {

  @Override
  public List<EvidenceCandidate> retrieve(ProjectIndex index, String query, int limit) {
    validate(query, limit);
    var normalized = query.strip().toLowerCase(Locale.ROOT);
    return index.symbols().stream()
        .map(symbol -> new Scored(symbol, score(symbol, normalized)))
        .filter(scored -> scored.score() > 0)
        .sorted(
            Comparator.comparingDouble(Scored::score)
                .reversed()
                .thenComparing(scored -> scored.symbol().id()))
        .limit(limit)
        .map(
            scored ->
                CandidateSupport.candidate(
                    scored.symbol(),
                    scored.score(),
                    RetrievalSource.EXACT,
                    "exact symbol/text match"))
        .toList();
  }

  private static double score(io.legacypilot.analysis.java.SourceSymbol symbol, String query) {
    if (symbol.qualifiedName().equalsIgnoreCase(query)) {
      return 1.0;
    }
    if (symbol.simpleName().equalsIgnoreCase(query)) {
      return 0.95;
    }
    if (symbol.signature().toLowerCase(Locale.ROOT).contains(query)) {
      return 0.85;
    }
    if (symbol.annotations().stream()
        .anyMatch(annotation -> annotation.toLowerCase(Locale.ROOT).contains(query))) {
      return 0.8;
    }
    if (symbol.sourceText().toLowerCase(Locale.ROOT).contains(query)) {
      return 0.65;
    }
    return 0;
  }

  static void validate(String query, int limit) {
    if (query == null || query.isBlank() || limit < 1 || limit > 10_000) {
      throw new IllegalArgumentException("retrieval query or limit is invalid");
    }
  }

  private record Scored(io.legacypilot.analysis.java.SourceSymbol symbol, double score) {}
}

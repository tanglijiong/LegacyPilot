package io.legacypilot.context;

import io.legacypilot.analysis.java.ProjectIndex;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

public final class HybridRetriever implements Retriever {

  private final List<WeightedRetriever> retrievers;

  public HybridRetriever(List<WeightedRetriever> retrievers) {
    this.retrievers = List.copyOf(retrievers);
    if (retrievers.isEmpty()) {
      throw new IllegalArgumentException("at least one retriever is required");
    }
  }

  public static HybridRetriever defaults() {
    return new HybridRetriever(
        List.of(
            new WeightedRetriever(new ExactSymbolRetriever(), 1.0),
            new WeightedRetriever(new Bm25Retriever(), 0.75),
            new WeightedRetriever(OptionalVectorRetriever.disabled(), 0.65)));
  }

  @Override
  public List<EvidenceCandidate> retrieve(ProjectIndex index, String query, int limit) {
    ExactSymbolRetriever.validate(query, limit);
    var merged = new LinkedHashMap<String, MutableCandidate>();
    for (var configured : retrievers) {
      var results = configured.retriever().retrieve(index, query, Math.max(limit, limit * 2));
      var maximum = results.stream().mapToDouble(EvidenceCandidate::score).max().orElse(1.0);
      for (var candidate : results) {
        var normalized = maximum == 0 ? 0 : candidate.score() / maximum;
        merged
            .computeIfAbsent(candidate.symbolId(), ignored -> new MutableCandidate(candidate))
            .merge(candidate, normalized * configured.weight());
      }
    }
    return merged.values().stream()
        .map(MutableCandidate::freeze)
        .sorted(
            Comparator.comparingDouble(EvidenceCandidate::score)
                .reversed()
                .thenComparing(EvidenceCandidate::symbolId))
        .limit(limit)
        .toList();
  }

  private static final class MutableCandidate {
    private final EvidenceCandidate base;
    private final LinkedHashSet<RetrievalSource> sources = new LinkedHashSet<>();
    private final List<String> reasons = new ArrayList<>();
    private double score;

    private MutableCandidate(EvidenceCandidate base) {
      this.base = base;
    }

    private void merge(EvidenceCandidate candidate, double contribution) {
      score += contribution;
      sources.addAll(candidate.sources());
      reasons.add(candidate.reason());
    }

    private EvidenceCandidate freeze() {
      return new EvidenceCandidate(
          base.referenceId(),
          base.symbolId(),
          base.path(),
          base.range(),
          base.summary(),
          score,
          sources,
          String.join("; ", new LinkedHashSet<>(reasons)));
    }
  }
}

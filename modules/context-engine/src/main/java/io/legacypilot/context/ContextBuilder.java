package io.legacypilot.context;

import io.legacypilot.analysis.java.DependencyGraph;
import io.legacypilot.analysis.java.GraphDirection;
import io.legacypilot.analysis.java.ProjectIndex;
import io.legacypilot.analysis.java.SourceSymbol;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ContextBuilder {

  private static final int MAXIMUM_CHUNKS_PER_FILE = 3;
  private final Retriever retriever;
  private final TokenEstimator tokens;

  public ContextBuilder(Retriever retriever, TokenEstimator tokens) {
    this.retriever = Objects.requireNonNull(retriever);
    this.tokens = Objects.requireNonNull(tokens);
  }

  public static ContextBuilder defaults() {
    return new ContextBuilder(HybridRetriever.defaults(), TokenEstimator.conservative());
  }

  public ContextBuildResult build(ProjectIndex index, ContextRequest request) {
    Objects.requireNonNull(index, "index must not be null");
    Objects.requireNonNull(request, "request must not be null");
    var direct = retriever.retrieve(index, request.query(), request.maximumCandidates());
    var expanded = expand(index, direct, request.graphDepth(), request.maximumCandidates() * 3);
    var ordered =
        expanded.values().stream()
            .map(ExpandedCandidate::freeze)
            .sorted(
                Comparator.comparingDouble(EvidenceCandidate::score)
                    .reversed()
                    .thenComparing(EvidenceCandidate::symbolId))
            .toList();
    return pack(index, ordered, request.tokenBudget());
  }

  private static Map<String, ExpandedCandidate> expand(
      ProjectIndex index, List<EvidenceCandidate> direct, int depth, int limit) {
    var merged = new LinkedHashMap<String, ExpandedCandidate>();
    direct.forEach(candidate -> merged.put(candidate.symbolId(), new ExpandedCandidate(candidate)));
    if (depth == 0 || direct.isEmpty()) {
      return merged;
    }
    var graph = new DependencyGraph(index);
    for (var candidate : direct) {
      for (var direction : GraphDirection.values()) {
        var hits =
            graph.traverse(candidate.symbolId(), direction, depth, java.util.Set.of(), limit);
        for (var hit : hits) {
          var score = candidate.score() * 0.5 / hit.depth();
          var graphCandidate =
              CandidateSupport.candidate(
                  hit.symbol(),
                  score,
                  RetrievalSource.GRAPH,
                  "dependency graph "
                      + direction.name().toLowerCase(java.util.Locale.ROOT)
                      + " depth "
                      + hit.depth());
          merged
              .computeIfAbsent(
                  graphCandidate.symbolId(), ignored -> new ExpandedCandidate(graphCandidate))
              .merge(graphCandidate);
        }
      }
    }
    return merged;
  }

  private ContextBuildResult pack(
      ProjectIndex index, List<EvidenceCandidate> candidates, int budget) {
    var selected = new ArrayList<ContextChunk>();
    var omitted = new ArrayList<ContextDecision>();
    var perFile = new HashMap<String, Integer>();
    var used = 0;
    for (var candidate : candidates) {
      var symbol = index.symbol(candidate.symbolId()).orElse(null);
      if (symbol == null) {
        omitted.add(new ContextDecision(candidate.symbolId(), "symbol missing from index"));
        continue;
      }
      if (perFile.getOrDefault(symbol.path(), 0) >= MAXIMUM_CHUNKS_PER_FILE) {
        omitted.add(new ContextDecision(symbol.id(), "per-file diversity limit"));
        continue;
      }
      var content = render(symbol, symbol.sourceText());
      var estimated = tokens.estimate(content);
      var reason = candidate.reason();
      if (estimated > budget - used) {
        content = render(symbol, CandidateSupport.summary(symbol));
        estimated = tokens.estimate(content);
        reason += "; signature summary used to fit budget";
      }
      if (estimated > budget - used) {
        omitted.add(new ContextDecision(symbol.id(), "token budget exhausted"));
        continue;
      }
      selected.add(
          new ContextChunk(
              candidate.referenceId(),
              symbol.id(),
              symbol.path(),
              symbol.range(),
              content,
              estimated,
              candidate.score(),
              candidate.sources(),
              reason));
      perFile.merge(symbol.path(), 1, Integer::sum);
      used += estimated;
    }
    return new ContextBuildResult(selected, omitted, used, budget);
  }

  private static String render(SourceSymbol symbol, String content) {
    return "["
        + CandidateSupport.referenceId(symbol.id())
        + "] "
        + symbol.path()
        + ":"
        + symbol.range().start().line()
        + " "
        + symbol.qualifiedName()
        + "\n"
        + content;
  }

  private static final class ExpandedCandidate {
    private final EvidenceCandidate base;
    private final LinkedHashSet<RetrievalSource> sources = new LinkedHashSet<>();
    private final LinkedHashSet<String> reasons = new LinkedHashSet<>();
    private double score;

    private ExpandedCandidate(EvidenceCandidate candidate) {
      this.base = candidate;
      merge(candidate);
    }

    private void merge(EvidenceCandidate candidate) {
      score = Math.max(score, candidate.score());
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
          String.join("; ", reasons));
    }
  }
}

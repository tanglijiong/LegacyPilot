package io.legacypilot.analysis.java;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class DependencyGraph {

  private final Map<String, SourceSymbol> symbols;
  private final List<DependencyEdge> edges;

  public DependencyGraph(ProjectIndex index) {
    Objects.requireNonNull(index, "index must not be null");
    this.symbols = new HashMap<>();
    index.symbols().forEach(symbol -> symbols.put(symbol.id(), symbol));
    this.edges = index.edges().stream().filter(DependencyEdge::resolved).toList();
  }

  public List<GraphHit> traverse(
      String symbolId,
      GraphDirection direction,
      int maximumDepth,
      Set<DependencyKind> allowedKinds,
      int limit) {
    if (!symbols.containsKey(symbolId)) {
      throw new IllegalArgumentException("unknown graph symbol: " + symbolId);
    }
    if (maximumDepth < 1 || limit < 1) {
      throw new IllegalArgumentException("graph limits must be positive");
    }
    var kinds = Set.copyOf(allowedKinds);
    var results = new ArrayList<GraphHit>();
    var visited = new HashSet<String>();
    visited.add(symbolId);
    var queue = new ArrayDeque<Node>();
    queue.add(new Node(symbolId, 0));
    while (!queue.isEmpty() && results.size() < limit) {
      var current = queue.removeFirst();
      if (current.depth() >= maximumDepth) {
        continue;
      }
      edges.stream()
          .filter(edge -> kinds.isEmpty() || kinds.contains(edge.kind()))
          .filter(edge -> touches(edge, current.id(), direction))
          .sorted(
              java.util.Comparator.comparing(DependencyEdge::kind)
                  .thenComparing(DependencyEdge::targetName))
          .forEach(
              edge -> {
                if (results.size() >= limit) {
                  return;
                }
                var nextId = next(edge, direction);
                var target = symbols.get(nextId);
                if (target != null && visited.add(nextId)) {
                  var depth = current.depth() + 1;
                  results.add(new GraphHit(target, edge, depth));
                  queue.addLast(new Node(nextId, depth));
                }
              });
    }
    return List.copyOf(results);
  }

  public Optional<List<DependencyEdge>> findPath(
      String fromSymbolId, String toSymbolId, int maximumDepth, Set<DependencyKind> allowedKinds) {
    if (!symbols.containsKey(fromSymbolId) || !symbols.containsKey(toSymbolId)) {
      throw new IllegalArgumentException("path endpoints must be indexed symbols");
    }
    if (maximumDepth < 1) {
      throw new IllegalArgumentException("path depth must be positive");
    }
    var kinds = Set.copyOf(allowedKinds);
    var visited = new HashSet<String>();
    visited.add(fromSymbolId);
    var queue = new ArrayDeque<PathNode>();
    queue.add(new PathNode(fromSymbolId, List.of()));
    while (!queue.isEmpty()) {
      var current = queue.removeFirst();
      if (current.edges().size() >= maximumDepth) {
        continue;
      }
      var outgoing =
          edges.stream()
              .filter(edge -> edge.fromSymbolId().equals(current.id()))
              .filter(edge -> kinds.isEmpty() || kinds.contains(edge.kind()))
              .sorted(
                  java.util.Comparator.comparing(DependencyEdge::kind)
                      .thenComparing(DependencyEdge::targetName))
              .toList();
      for (var edge : outgoing) {
        var path = new ArrayList<>(current.edges());
        path.add(edge);
        if (edge.toSymbolId().equals(toSymbolId)) {
          return Optional.of(List.copyOf(path));
        }
        if (visited.add(edge.toSymbolId())) {
          queue.addLast(new PathNode(edge.toSymbolId(), List.copyOf(path)));
        }
      }
    }
    return Optional.empty();
  }

  private static boolean touches(DependencyEdge edge, String symbolId, GraphDirection direction) {
    return direction == GraphDirection.DOWNSTREAM
        ? edge.fromSymbolId().equals(symbolId)
        : edge.toSymbolId().equals(symbolId);
  }

  private static String next(DependencyEdge edge, GraphDirection direction) {
    return direction == GraphDirection.DOWNSTREAM ? edge.toSymbolId() : edge.fromSymbolId();
  }

  private record Node(String id, int depth) {}

  private record PathNode(String id, List<DependencyEdge> edges) {}
}

package io.legacypilot.analysis.java;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ProjectIndex(
    int schemaVersion,
    String revision,
    List<SourceSymbol> symbols,
    List<DependencyEdge> edges,
    List<IndexProblem> problems) {

  public static final int CURRENT_SCHEMA_VERSION = 1;

  public ProjectIndex {
    Objects.requireNonNull(revision, "revision must not be null");
    Objects.requireNonNull(symbols, "symbols must not be null");
    Objects.requireNonNull(edges, "edges must not be null");
    Objects.requireNonNull(problems, "problems must not be null");
    if (schemaVersion < 1 || revision.isBlank()) {
      throw new IllegalArgumentException("project index identity is invalid");
    }
    symbols = List.copyOf(symbols);
    edges = List.copyOf(edges);
    problems = List.copyOf(problems);
  }

  public Optional<SourceSymbol> symbol(String id) {
    return symbols.stream().filter(symbol -> symbol.id().equals(id)).findFirst();
  }

  public List<SourceSymbol> named(String name) {
    return symbols.stream()
        .filter(
            symbol ->
                symbol.simpleName().equals(name)
                    || symbol.qualifiedName().equals(name)
                    || symbol.signature().equals(name))
        .toList();
  }
}

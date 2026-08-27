package io.legacypilot.analysis.java;

import java.util.Objects;
import java.util.Set;

public record SourceSymbol(
    String id,
    SymbolKind kind,
    String simpleName,
    String qualifiedName,
    String signature,
    String path,
    SourceRange range,
    Set<String> modifiers,
    Set<String> annotations,
    Set<SpringRole> springRoles,
    String javadoc,
    String sourceText,
    boolean testSource) {

  public SourceSymbol {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(simpleName, "simpleName must not be null");
    Objects.requireNonNull(qualifiedName, "qualifiedName must not be null");
    Objects.requireNonNull(signature, "signature must not be null");
    Objects.requireNonNull(path, "path must not be null");
    Objects.requireNonNull(range, "range must not be null");
    Objects.requireNonNull(modifiers, "modifiers must not be null");
    Objects.requireNonNull(annotations, "annotations must not be null");
    Objects.requireNonNull(springRoles, "springRoles must not be null");
    Objects.requireNonNull(javadoc, "javadoc must not be null");
    Objects.requireNonNull(sourceText, "sourceText must not be null");
    if (id.isBlank() || simpleName.isBlank() || path.isBlank()) {
      throw new IllegalArgumentException("symbol identity must not be blank");
    }
    modifiers = Set.copyOf(modifiers);
    annotations = Set.copyOf(annotations);
    springRoles = Set.copyOf(springRoles);
  }
}

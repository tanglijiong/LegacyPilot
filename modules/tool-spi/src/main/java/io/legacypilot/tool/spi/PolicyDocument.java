package io.legacypilot.tool.spi;

import java.util.List;
import java.util.Objects;

public record PolicyDocument(int schemaVersion, String revision, List<PolicyRule> rules) {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public PolicyDocument {
    Objects.requireNonNull(revision, "policy revision must not be null");
    rules = rules == null ? List.of() : List.copyOf(rules);
    if (schemaVersion != CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported policy schema version: " + schemaVersion);
    }
    if (!revision.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,95}")) {
      throw new IllegalArgumentException("policy revision is invalid");
    }
    if (rules.isEmpty()) {
      throw new IllegalArgumentException("policy must contain at least one rule");
    }
    if (rules.stream().map(PolicyRule::id).distinct().count() != rules.size()) {
      throw new IllegalArgumentException("policy rule ids must be unique");
    }
  }
}

package io.legacypilot.observability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Structured, source-grounded output produced by ANALYZE and EXPLAIN tasks. */
public record TaskArtifact(
    int schemaVersion,
    String taskMode,
    String summary,
    Map<String, List<String>> sections,
    List<SourceEvidence> evidence,
    List<String> unknowns) {

  public static final int CURRENT_SCHEMA_VERSION = 1;

  public TaskArtifact {
    Objects.requireNonNull(taskMode, "artifact task mode is required");
    Objects.requireNonNull(summary, "artifact summary is required");
    Objects.requireNonNull(sections, "artifact sections are required");
    Objects.requireNonNull(evidence, "artifact evidence is required");
    Objects.requireNonNull(unknowns, "artifact unknowns are required");
    if (schemaVersion != CURRENT_SCHEMA_VERSION || taskMode.isBlank() || summary.isBlank()) {
      throw new IllegalArgumentException("task artifact identity is invalid");
    }
    var copied = new LinkedHashMap<String, List<String>>();
    sections.forEach((name, values) -> copied.put(name, List.copyOf(values)));
    sections = Collections.unmodifiableMap(copied);
    evidence = List.copyOf(evidence);
    unknowns = List.copyOf(unknowns);
  }
}

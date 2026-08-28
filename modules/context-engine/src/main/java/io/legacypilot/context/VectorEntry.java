package io.legacypilot.context;

import io.legacypilot.analysis.java.SourceRange;
import java.util.List;
import java.util.Objects;

public record VectorEntry(
    String revision,
    String model,
    String fileDigest,
    String symbolId,
    String path,
    SourceRange range,
    String summary,
    List<Double> vector) {
  public VectorEntry {
    Objects.requireNonNull(revision);
    Objects.requireNonNull(model);
    Objects.requireNonNull(fileDigest);
    Objects.requireNonNull(symbolId);
    Objects.requireNonNull(path);
    Objects.requireNonNull(range);
    Objects.requireNonNull(summary);
    vector = vector == null ? List.of() : List.copyOf(vector);
    if (revision.isBlank()
        || model.isBlank()
        || !fileDigest.matches("[a-f0-9]{64}")
        || symbolId.isBlank()
        || path.isBlank()
        || vector.isEmpty()) {
      throw new IllegalArgumentException("vector entry is invalid");
    }
  }
}

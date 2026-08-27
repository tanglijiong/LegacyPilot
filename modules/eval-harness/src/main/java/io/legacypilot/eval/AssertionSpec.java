package io.legacypilot.eval;

import java.util.Objects;

public record AssertionSpec(String type, String path, String value) {
  public AssertionSpec {
    Objects.requireNonNull(type);
    Objects.requireNonNull(path);
    value = Objects.requireNonNullElse(value, "");
    if (!java.util.Set.of("FILE_EXISTS", "CONTAINS", "NOT_CONTAINS").contains(type)
        || path.isBlank()) {
      throw new IllegalArgumentException("eval assertion is invalid");
    }
  }
}

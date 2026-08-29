package io.legacypilot.eval;

import java.util.Objects;

public record AssertionSpec(String type, String path, String value) {
  private static final java.util.Set<String> SUPPORTED_TYPES =
      java.util.Set.of(
          "FILE_EXISTS",
          "FILE_NOT_EXISTS",
          "CONTAINS",
          "NOT_CONTAINS",
          "MATCHES_REGEX",
          "NOT_MATCHES_REGEX",
          "SHA256_EQUALS");

  public AssertionSpec {
    Objects.requireNonNull(type);
    Objects.requireNonNull(path);
    value = Objects.requireNonNullElse(value, "");
    if (!SUPPORTED_TYPES.contains(type) || path.isBlank()) {
      throw new IllegalArgumentException("eval assertion is invalid");
    }
  }
}

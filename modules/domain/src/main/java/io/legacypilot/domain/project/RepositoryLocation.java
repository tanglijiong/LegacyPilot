package io.legacypilot.domain.project;

import java.util.Objects;

/** User-provided local path or public HTTP(S) Git repository URL. */
public record RepositoryLocation(String value) {

  public RepositoryLocation {
    Objects.requireNonNull(value, "value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("value must not be blank");
    }
  }
}

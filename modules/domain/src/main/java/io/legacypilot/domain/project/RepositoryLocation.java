package io.legacypilot.domain.project;

import java.util.Objects;

/** Local project path, managed directory snapshot token, or public HTTP(S) Git repository URL. */
public record RepositoryLocation(String value) {

  public RepositoryLocation {
    Objects.requireNonNull(value, "value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("value must not be blank");
    }
  }
}

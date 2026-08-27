package io.legacypilot.domain.project;

import java.util.Locale;
import java.util.Objects;

/** Immutable Git commit identifier used to prevent source drift during a run. */
public record GitRevision(String value) {

  public GitRevision {
    Objects.requireNonNull(value, "value must not be null");
    if (!value.matches("[0-9a-fA-F]{40,64}")) {
      throw new IllegalArgumentException("value must be a full Git object id");
    }
    value = value.toLowerCase(Locale.ROOT);
  }
}

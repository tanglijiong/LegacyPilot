package io.legacypilot.domain.project;

import java.util.Objects;

/** Stable identifier for a project known to LegacyPilot. */
public record ProjectId(String value) {

  public ProjectId {
    Objects.requireNonNull(value, "value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("value must not be blank");
    }
  }
}

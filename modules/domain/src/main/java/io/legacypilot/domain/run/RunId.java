package io.legacypilot.domain.run;

import java.util.Objects;

public record RunId(String value) {

  public RunId {
    Objects.requireNonNull(value, "value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("value must not be blank");
    }
  }
}

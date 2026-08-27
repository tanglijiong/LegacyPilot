package io.legacypilot.domain.task;

import java.util.Objects;

public record TaskId(String value) {

  public TaskId {
    Objects.requireNonNull(value, "value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("value must not be blank");
    }
  }
}

package io.legacypilot.domain.task;

import java.util.Objects;

public record Requirement(String text) {

  public Requirement {
    Objects.requireNonNull(text, "text must not be null");
    if (text.isBlank()) {
      throw new IllegalArgumentException("text must not be blank");
    }
  }
}

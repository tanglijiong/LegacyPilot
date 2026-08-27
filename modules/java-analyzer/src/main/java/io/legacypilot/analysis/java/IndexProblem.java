package io.legacypilot.analysis.java;

import java.util.Objects;

public record IndexProblem(String path, int line, String message) {

  public IndexProblem {
    Objects.requireNonNull(path, "path must not be null");
    Objects.requireNonNull(message, "message must not be null");
    if (path.isBlank() || line < 1 || message.isBlank()) {
      throw new IllegalArgumentException("index problem is invalid");
    }
  }
}

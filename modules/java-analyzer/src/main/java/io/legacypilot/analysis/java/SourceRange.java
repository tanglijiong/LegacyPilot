package io.legacypilot.analysis.java;

import java.util.Objects;

public record SourceRange(SourcePosition start, SourcePosition end) {

  public SourceRange {
    Objects.requireNonNull(start, "start must not be null");
    Objects.requireNonNull(end, "end must not be null");
  }
}

package io.legacypilot.analysis.java;

public record SourcePosition(int line, int column) {

  public SourcePosition {
    if (line < 1 || column < 1) {
      throw new IllegalArgumentException("source position must be positive");
    }
  }
}

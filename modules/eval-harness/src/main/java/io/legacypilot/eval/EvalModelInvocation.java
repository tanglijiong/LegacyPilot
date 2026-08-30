package io.legacypilot.eval;

import java.util.Objects;

public record EvalModelInvocation(int exitCode, EvalTokenUsage usage, int steps) {
  public EvalModelInvocation {
    Objects.requireNonNull(usage);
    if (exitCode < 0 || steps < 0) {
      throw new IllegalArgumentException("eval model invocation is invalid");
    }
  }
}

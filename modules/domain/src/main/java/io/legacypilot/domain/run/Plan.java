package io.legacypilot.domain.run;

import java.util.List;
import java.util.Objects;

public record Plan(int version, List<String> steps, List<String> affectedComponents, String risk) {

  public Plan {
    if (version < 1) {
      throw new IllegalArgumentException("version must be positive");
    }
    Objects.requireNonNull(steps, "steps must not be null");
    Objects.requireNonNull(affectedComponents, "affectedComponents must not be null");
    Objects.requireNonNull(risk, "risk must not be null");
    steps = List.copyOf(steps);
    affectedComponents = List.copyOf(affectedComponents);
  }
}

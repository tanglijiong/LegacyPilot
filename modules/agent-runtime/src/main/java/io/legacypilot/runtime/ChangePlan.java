package io.legacypilot.runtime;

import java.util.List;
import java.util.Objects;

public record ChangePlan(
    int version, List<String> steps, List<String> affectedFiles, String risk, String rationale) {

  public ChangePlan {
    if (version < 1) {
      throw new IllegalArgumentException("plan version must be positive");
    }
    steps = List.copyOf(Objects.requireNonNull(steps));
    affectedFiles = List.copyOf(Objects.requireNonNull(affectedFiles));
    Objects.requireNonNull(risk);
    Objects.requireNonNull(rationale);
    if (steps.isEmpty() || risk.isBlank()) {
      throw new IllegalArgumentException("plan is incomplete");
    }
  }
}

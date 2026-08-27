package io.legacypilot.eval;

import java.util.List;
import java.util.Objects;

public record EvalTask(
    String id,
    String category,
    String requirement,
    String fixtureRevision,
    List<String> expectedFiles,
    List<String> relevantSymbols,
    int maximumSteps,
    List<AssertionSpec> assertions) {
  public EvalTask {
    Objects.requireNonNull(id);
    Objects.requireNonNull(category);
    Objects.requireNonNull(requirement);
    Objects.requireNonNull(fixtureRevision);
    expectedFiles = List.copyOf(Objects.requireNonNull(expectedFiles));
    relevantSymbols = List.copyOf(Objects.requireNonNull(relevantSymbols));
    assertions = List.copyOf(Objects.requireNonNull(assertions));
    if (!id.matches("task-[0-9]{3}")
        || category.isBlank()
        || requirement.isBlank()
        || fixtureRevision.isBlank()
        || maximumSteps < 1
        || assertions.isEmpty()) {
      throw new IllegalArgumentException("eval task is invalid");
    }
  }
}

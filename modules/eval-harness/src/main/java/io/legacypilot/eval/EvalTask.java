package io.legacypilot.eval;

import java.util.List;
import java.util.Objects;

public record EvalTask(
    String id,
    String category,
    String difficulty,
    String changeType,
    String expectedImpact,
    String requirement,
    String fixtureId,
    String fixtureRevision,
    List<String> allowedFiles,
    List<String> forbiddenFiles,
    List<String> expectedFiles,
    List<String> relevantSymbols,
    int maximumSteps,
    int timeoutSeconds,
    ResourceBudget resourceBudget,
    List<AssertionSpec> assertions) {
  public EvalTask {
    Objects.requireNonNull(id);
    Objects.requireNonNull(category);
    Objects.requireNonNull(difficulty);
    Objects.requireNonNull(changeType);
    Objects.requireNonNull(expectedImpact);
    Objects.requireNonNull(requirement);
    Objects.requireNonNull(fixtureId);
    Objects.requireNonNull(fixtureRevision);
    allowedFiles = List.copyOf(Objects.requireNonNull(allowedFiles));
    forbiddenFiles = List.copyOf(Objects.requireNonNull(forbiddenFiles));
    expectedFiles = List.copyOf(Objects.requireNonNull(expectedFiles));
    relevantSymbols = List.copyOf(Objects.requireNonNull(relevantSymbols));
    Objects.requireNonNull(resourceBudget);
    assertions = List.copyOf(Objects.requireNonNull(assertions));
    if (!id.matches("task-[0-9]{3}")
        || category.isBlank()
        || difficulty.isBlank()
        || changeType.isBlank()
        || expectedImpact.isBlank()
        || requirement.isBlank()
        || fixtureId.isBlank()
        || fixtureRevision.isBlank()
        || allowedFiles.isEmpty()
        || maximumSteps < 1
        || timeoutSeconds < 1
        || assertions.isEmpty()) {
      throw new IllegalArgumentException("eval task is invalid");
    }
    if (!allowedFiles.containsAll(expectedFiles)
        || allowedFiles.stream().anyMatch(forbiddenFiles::contains)) {
      throw new IllegalArgumentException("eval task file scope is inconsistent");
    }
    allowedFiles.forEach(EvalTask::validateRelativePath);
    forbiddenFiles.forEach(EvalTask::validateRelativePath);
    expectedFiles.forEach(EvalTask::validateRelativePath);
  }

  private static void validateRelativePath(String value) {
    var path = java.nio.file.Path.of(Objects.requireNonNull(value)).normalize();
    if (value.isBlank() || path.isAbsolute() || path.startsWith("..")) {
      throw new IllegalArgumentException("eval task path is invalid");
    }
  }

  public record ResourceBudget(int maximumTokens, int maximumMemoryMb, int maximumCostCents) {
    public ResourceBudget {
      if (maximumTokens < 1 || maximumMemoryMb < 1 || maximumCostCents < 0) {
        throw new IllegalArgumentException("eval task resource budget is invalid");
      }
    }
  }
}

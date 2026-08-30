package io.legacypilot.eval;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record EvalExperimentManifest(
    String schemaVersion,
    String runId,
    String repositoryCommit,
    String datasetVersion,
    String datasetSha256,
    String model,
    String reasoningEffort,
    String promptVersion,
    String promptSha256,
    String policyVersion,
    EvalPricingSnapshot pricing,
    Map<String, String> environment,
    EvalExperimentBudget budget,
    List<String> taskIds,
    Instant createdAt) {
  public EvalExperimentManifest {
    Objects.requireNonNull(schemaVersion);
    Objects.requireNonNull(runId);
    Objects.requireNonNull(repositoryCommit);
    Objects.requireNonNull(datasetVersion);
    Objects.requireNonNull(datasetSha256);
    Objects.requireNonNull(model);
    Objects.requireNonNull(reasoningEffort);
    Objects.requireNonNull(promptVersion);
    Objects.requireNonNull(promptSha256);
    Objects.requireNonNull(policyVersion);
    Objects.requireNonNull(pricing);
    environment = Map.copyOf(Objects.requireNonNull(environment));
    Objects.requireNonNull(budget);
    taskIds = List.copyOf(Objects.requireNonNull(taskIds));
    Objects.requireNonNull(createdAt);
    if (!schemaVersion.equals("eval-experiment-v1")
        || !runId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")
        || !repositoryCommit.matches("[0-9a-f]{7,40}")
        || datasetVersion.isBlank()
        || !datasetSha256.matches("[0-9a-f]{64}")
        || model.isBlank()
        || !reasoningEffort.matches("low|medium|high|xhigh|max|ultra")
        || promptVersion.isBlank()
        || !promptSha256.matches("[0-9a-f]{64}")
        || policyVersion.isBlank()
        || taskIds.isEmpty()
        || taskIds.stream().distinct().count() != taskIds.size()) {
      throw new IllegalArgumentException("eval experiment manifest is invalid");
    }
    environment.keySet().forEach(EvalExperimentManifest::rejectSensitiveKey);
  }

  private static void rejectSensitiveKey(String key) {
    var normalized = Objects.requireNonNull(key).toLowerCase(Locale.ROOT);
    if (normalized.contains("token")
        || normalized.contains("secret")
        || normalized.contains("password")
        || normalized.contains("api_key")
        || normalized.contains("apikey")
        || normalized.contains("authorization")) {
      throw new IllegalArgumentException("eval environment must not contain credential fields");
    }
  }
}

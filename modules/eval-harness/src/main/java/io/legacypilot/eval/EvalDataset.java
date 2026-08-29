package io.legacypilot.eval;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record EvalDataset(
    String schemaVersion,
    String datasetVersion,
    String datasetSha256,
    Map<String, FixtureDefinition> fixtures,
    List<EvalTask> tasks) {
  public EvalDataset {
    Objects.requireNonNull(schemaVersion);
    Objects.requireNonNull(datasetVersion);
    Objects.requireNonNull(datasetSha256);
    fixtures = Map.copyOf(Objects.requireNonNull(fixtures));
    tasks = List.copyOf(Objects.requireNonNull(tasks));
    if (!schemaVersion.equals("eval-dataset-v2")
        || datasetVersion.isBlank()
        || !datasetSha256.matches("[0-9a-f]{64}")
        || fixtures.isEmpty()
        || tasks.isEmpty()) {
      throw new IllegalArgumentException("eval dataset manifest is invalid");
    }
  }
}

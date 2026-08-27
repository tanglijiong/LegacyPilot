package io.legacypilot.observability;

import java.util.Optional;

public interface ReportStore {
  void save(RunReport report);

  Optional<RunReport> load(String runId);
}

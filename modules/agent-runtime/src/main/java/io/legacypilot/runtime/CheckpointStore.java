package io.legacypilot.runtime;

import java.util.Optional;

public interface CheckpointStore {
  void save(AgentCheckpoint checkpoint);

  Optional<AgentCheckpoint> load(String runId);
}

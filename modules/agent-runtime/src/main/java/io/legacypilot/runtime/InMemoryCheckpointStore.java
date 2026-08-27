package io.legacypilot.runtime;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryCheckpointStore implements CheckpointStore {
  private final ConcurrentHashMap<String, AgentCheckpoint> values = new ConcurrentHashMap<>();

  @Override
  public void save(AgentCheckpoint checkpoint) {
    values.put(checkpoint.runId(), checkpoint);
  }

  @Override
  public Optional<AgentCheckpoint> load(String runId) {
    return Optional.ofNullable(values.get(runId));
  }
}

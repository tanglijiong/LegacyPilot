package io.legacypilot.runtime;

import java.util.Optional;

public interface AgentRunRequestStore {
  void save(AgentRunRequest request);

  Optional<AgentRunRequest> load(String runId);
}

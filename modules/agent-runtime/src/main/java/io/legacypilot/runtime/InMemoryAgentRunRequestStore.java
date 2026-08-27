package io.legacypilot.runtime;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryAgentRunRequestStore implements AgentRunRequestStore {
  private final ConcurrentHashMap<String, AgentRunRequest> requests = new ConcurrentHashMap<>();

  @Override
  public void save(AgentRunRequest request) {
    requests.put(request.runId(), request);
  }

  @Override
  public Optional<AgentRunRequest> load(String runId) {
    return Optional.ofNullable(requests.get(runId));
  }
}

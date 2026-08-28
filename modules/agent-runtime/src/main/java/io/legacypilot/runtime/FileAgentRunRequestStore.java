package io.legacypilot.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.state.StateInspection;
import io.legacypilot.state.VersionedJsonFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public final class FileAgentRunRequestStore implements AgentRunRequestStore {
  private final Path directory;
  private final ObjectMapper mapper;

  public FileAgentRunRequestStore(Path directory, ObjectMapper mapper) {
    this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
    this.mapper = Objects.requireNonNull(mapper);
    try {
      Files.createDirectories(this.directory);
    } catch (IOException exception) {
      throw new IllegalArgumentException("request directory is unavailable", exception);
    }
  }

  @Override
  public synchronized void save(AgentRunRequest request) {
    file(request.runId()).save(request);
  }

  @Override
  public synchronized Optional<AgentRunRequest> load(String runId) {
    return file(runId).load();
  }

  public StateInspection inspect(String runId) {
    return file(runId).inspect();
  }

  private VersionedJsonFile<AgentRunRequest> file(String runId) {
    validate(runId);
    return new VersionedJsonFile<>(
        directory.resolve(runId + ".json"),
        mapper,
        mapper.getTypeFactory().constructType(AgentRunRequest.class));
  }

  private static void validate(String runId) {
    if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")) {
      throw new IllegalArgumentException("run id is invalid");
    }
  }
}

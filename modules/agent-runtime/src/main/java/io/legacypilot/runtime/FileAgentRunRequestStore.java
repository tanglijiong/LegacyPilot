package io.legacypilot.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    var target = path(request.runId());
    try {
      var temporary = Files.createTempFile(directory, ".request-", ".tmp");
      try {
        mapper.writeValue(temporary.toFile(), request);
        Files.move(
            temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("unable to persist agent request", exception);
    }
  }

  @Override
  public synchronized Optional<AgentRunRequest> load(String runId) {
    var target = path(runId);
    if (!Files.exists(target)) {
      return Optional.empty();
    }
    try {
      return Optional.of(mapper.readValue(target.toFile(), AgentRunRequest.class));
    } catch (IOException exception) {
      throw new IllegalStateException("unable to load agent request", exception);
    }
  }

  private Path path(String runId) {
    if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")) {
      throw new IllegalArgumentException("run id is invalid");
    }
    return directory.resolve(runId + ".json");
  }
}

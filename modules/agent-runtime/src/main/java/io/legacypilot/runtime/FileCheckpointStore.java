package io.legacypilot.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;

public final class FileCheckpointStore implements CheckpointStore {
  private final Path directory;
  private final ObjectMapper mapper;

  public FileCheckpointStore(Path directory, ObjectMapper mapper) {
    this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
    this.mapper = Objects.requireNonNull(mapper);
    try {
      Files.createDirectories(this.directory);
    } catch (IOException exception) {
      throw new IllegalArgumentException("checkpoint directory is unavailable", exception);
    }
  }

  @Override
  public synchronized void save(AgentCheckpoint checkpoint) {
    var target = path(checkpoint.runId());
    try {
      var temporary = Files.createTempFile(directory, ".checkpoint-", ".tmp");
      try {
        mapper.writeValue(temporary.toFile(), checkpoint);
        Files.move(
            temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("unable to persist agent checkpoint", exception);
    }
  }

  @Override
  public synchronized Optional<AgentCheckpoint> load(String runId) {
    var target = path(runId);
    if (!Files.exists(target)) {
      return Optional.empty();
    }
    try {
      return Optional.of(mapper.readValue(target.toFile(), AgentCheckpoint.class));
    } catch (IOException exception) {
      throw new IllegalStateException("unable to load agent checkpoint", exception);
    }
  }

  private Path path(String runId) {
    if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")) {
      throw new IllegalArgumentException("run id is invalid");
    }
    return directory.resolve(runId + ".json");
  }
}

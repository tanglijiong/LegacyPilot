package io.legacypilot.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.state.StateInspection;
import io.legacypilot.state.VersionedJsonFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    file(checkpoint.runId()).save(checkpoint);
  }

  @Override
  public synchronized Optional<AgentCheckpoint> load(String runId) {
    return file(runId).load();
  }

  public synchronized List<String> runIds() {
    try (var paths = Files.list(directory)) {
      return paths
          .map(path -> Objects.requireNonNull(path.getFileName()).toString())
          .filter(name -> name.endsWith(".json"))
          .map(name -> name.replaceFirst("\\.json$", ""))
          .sorted()
          .toList();
    } catch (IOException exception) {
      throw new IllegalStateException("unable to list checkpoints", exception);
    }
  }

  public StateInspection inspect(String runId) {
    return file(runId).inspect();
  }

  private VersionedJsonFile<AgentCheckpoint> file(String runId) {
    validate(runId);
    return new VersionedJsonFile<>(
        directory.resolve(runId + ".json"),
        mapper,
        mapper.getTypeFactory().constructType(AgentCheckpoint.class));
  }

  private static void validate(String runId) {
    if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")) {
      throw new IllegalArgumentException("run id is invalid");
    }
  }
}

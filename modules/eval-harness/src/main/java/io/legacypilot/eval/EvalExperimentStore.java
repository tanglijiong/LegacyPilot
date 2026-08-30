package io.legacypilot.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.state.VersionedJsonFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;

public final class EvalExperimentStore {
  private final Path root;
  private final Path manifestPath;
  private final ObjectMapper mapper;
  private final VersionedJsonFile<EvalExperimentCheckpoint> checkpoint;

  public EvalExperimentStore(Path root, ObjectMapper mapper) {
    this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
    this.manifestPath = this.root.resolve("manifest.json");
    this.mapper = Objects.requireNonNull(mapper);
    try {
      Files.createDirectories(this.root);
    } catch (IOException exception) {
      throw new IllegalArgumentException("eval experiment directory is unavailable", exception);
    }
    this.checkpoint =
        new VersionedJsonFile<>(
            this.root.resolve("checkpoint.json"),
            mapper,
            mapper.getTypeFactory().constructType(EvalExperimentCheckpoint.class));
  }

  public synchronized void createManifest(EvalExperimentManifest manifest) {
    Objects.requireNonNull(manifest);
    if (Files.exists(manifestPath)) {
      throw new IllegalStateException("eval experiment manifest already exists");
    }
    try {
      var temporary = Files.createTempFile(root, ".manifest-", ".tmp");
      try {
        mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), manifest);
        try {
          Files.move(temporary, manifestPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
          Files.move(temporary, manifestPath);
        }
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (java.nio.file.FileAlreadyExistsException exception) {
      throw new IllegalStateException("eval experiment manifest already exists", exception);
    } catch (IOException exception) {
      throw new IllegalStateException("unable to create eval experiment manifest", exception);
    }
  }

  public synchronized EvalExperimentManifest manifest() {
    try {
      return mapper.readValue(manifestPath.toFile(), EvalExperimentManifest.class);
    } catch (IOException exception) {
      throw new IllegalStateException("unable to read eval experiment manifest", exception);
    }
  }

  public synchronized void save(EvalExperimentCheckpoint value) {
    checkpoint.save(value);
  }

  public synchronized Optional<EvalExperimentCheckpoint> load() {
    return checkpoint.load();
  }

  public Path root() {
    return root;
  }
}

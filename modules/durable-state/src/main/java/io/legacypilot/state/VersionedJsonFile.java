package io.legacypilot.state;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

public final class VersionedJsonFile<T> {
  public static final int CURRENT_VERSION = 2;

  private final Path path;
  private final ObjectMapper mapper;
  private final JavaType type;
  private final Clock clock;

  public VersionedJsonFile(Path path, ObjectMapper mapper, JavaType type) {
    this(path, mapper, type, Clock.systemUTC());
  }

  public VersionedJsonFile(Path path, ObjectMapper mapper, JavaType type, Clock clock) {
    this.path = Objects.requireNonNull(path).toAbsolutePath().normalize();
    this.mapper = Objects.requireNonNull(mapper);
    this.type = Objects.requireNonNull(type);
    this.clock = Objects.requireNonNull(clock);
    try {
      var parent = this.path.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
    } catch (IOException exception) {
      throw new IllegalArgumentException("state path is unavailable", exception);
    }
  }

  public synchronized void save(T value) {
    Objects.requireNonNull(value);
    var envelope = mapper.createObjectNode();
    envelope.put("schemaVersion", CURRENT_VERSION);
    envelope.put("writtenAt", clock.instant().toString());
    envelope.set("payload", mapper.valueToTree(value));
    write(envelope, true);
  }

  public synchronized Optional<T> load() {
    if (!Files.exists(path)) {
      return Optional.empty();
    }
    try {
      var root = mapper.readTree(path.toFile());
      if (isEnvelope(root)) {
        var version = root.path("schemaVersion").asInt(-1);
        if (version > CURRENT_VERSION || version < 1) {
          throw new UnsupportedStateVersionException(
              "unsupported state schema version " + version + " for " + path.getFileName());
        }
        return Optional.ofNullable(read(root.get("payload")));
      }
      var legacy = read(root);
      backupLegacy();
      save(legacy);
      return Optional.ofNullable(legacy);
    } catch (UnsupportedStateVersionException exception) {
      throw exception;
    } catch (IOException | IllegalArgumentException exception) {
      quarantine();
      throw new IllegalStateException(
          "unable to read durable state " + path.getFileName(), exception);
    }
  }

  public synchronized StateInspection inspect() {
    if (!Files.exists(path)) {
      return new StateInspection(path, StateHealth.MISSING, 0, "state file does not exist");
    }
    try {
      var root = mapper.readTree(path.toFile());
      if (!isEnvelope(root)) {
        read(root);
        return new StateInspection(path, StateHealth.LEGACY, 1, "legacy payload can be migrated");
      }
      var version = root.path("schemaVersion").asInt(-1);
      if (version > CURRENT_VERSION || version < 1) {
        return new StateInspection(path, StateHealth.UNSUPPORTED, version, "unsupported schema");
      }
      read(root.get("payload"));
      return new StateInspection(path, StateHealth.CURRENT, version, "state is readable");
    } catch (IOException | IllegalArgumentException exception) {
      return new StateInspection(path, StateHealth.CORRUPT, -1, "state is not readable");
    }
  }

  public Path path() {
    return path;
  }

  private static boolean isEnvelope(JsonNode root) {
    return root instanceof ObjectNode && root.has("schemaVersion") && root.has("payload");
  }

  private T read(JsonNode node) throws IOException {
    return mapper.readerFor(type).readValue(node.traverse(mapper));
  }

  private void backupLegacy() throws IOException {
    var backup = path.resolveSibling(path.getFileName() + ".v1.bak");
    if (!Files.exists(backup)) {
      Files.copy(path, backup);
    }
  }

  private void write(JsonNode value, boolean backupCurrent) {
    var parent = Objects.requireNonNull(path.getParent());
    try {
      if (backupCurrent && Files.exists(path)) {
        Files.copy(
            path,
            path.resolveSibling(path.getFileName() + ".previous"),
            StandardCopyOption.REPLACE_EXISTING);
      }
      var temporary = Files.createTempFile(parent, ".state-", ".tmp");
      try {
        mapper.writeValue(temporary.toFile(), value);
        try {
          Files.move(
              temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
          Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException exception) {
      throw new IllegalStateException(
          "unable to write durable state " + path.getFileName(), exception);
    }
  }

  private void quarantine() {
    try {
      if (Files.exists(path)) {
        var quarantine = path.resolveSibling(path.getFileName() + ".corrupt");
        Files.copy(path, quarantine, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException ignored) {
      // Preserve the original read failure; quarantine is best effort.
    }
  }
}

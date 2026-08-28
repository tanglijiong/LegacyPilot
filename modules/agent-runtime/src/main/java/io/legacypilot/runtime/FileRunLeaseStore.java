package io.legacypilot.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.state.VersionedJsonFile;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class FileRunLeaseStore implements RunLeaseStore {
  private static final java.util.concurrent.ConcurrentHashMap<Path, Object> JVM_LOCKS =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final Path directory;
  private final ObjectMapper mapper;

  public FileRunLeaseStore(Path directory, ObjectMapper mapper) {
    this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
    this.mapper = Objects.requireNonNull(mapper);
    try {
      Files.createDirectories(this.directory);
    } catch (IOException exception) {
      throw new IllegalArgumentException("lease directory is unavailable", exception);
    }
  }

  @Override
  public Optional<RunLease> acquire(String runId, String owner, Instant now, Duration ttl) {
    return locked(
        runId,
        () -> {
          var file = file(runId);
          var current = file.load().orElse(null);
          if (current != null && current.activeAt(now) && !current.owner().equals(owner)) {
            return Optional.empty();
          }
          var epoch =
              current == null
                  ? 1
                  : current.activeAt(now) && current.owner().equals(owner)
                      ? current.epoch()
                      : current.epoch() + 1;
          var value = new RunLease(runId, owner, epoch, now.plus(ttl));
          file.save(value);
          return Optional.of(value);
        });
  }

  @Override
  public Optional<RunLease> renew(RunLease lease, Instant now, Duration ttl) {
    return locked(
        lease.runId(),
        () -> {
          var file = file(lease.runId());
          var current = file.load().orElse(null);
          if (current == null
              || current.epoch() != lease.epoch()
              || !current.owner().equals(lease.owner())
              || !current.activeAt(now)) {
            return Optional.empty();
          }
          var value = new RunLease(lease.runId(), lease.owner(), lease.epoch(), now.plus(ttl));
          file.save(value);
          return Optional.of(value);
        });
  }

  @Override
  public void release(RunLease lease) {
    locked(
        lease.runId(),
        () -> {
          var current = file(lease.runId()).load().orElse(null);
          if (current != null
              && current.epoch() == lease.epoch()
              && current.owner().equals(lease.owner())) {
            file(lease.runId())
                .save(
                    new RunLease(current.runId(), current.owner(), current.epoch(), Instant.EPOCH));
          }
          return null;
        });
  }

  @Override
  public Optional<RunLease> current(String runId) {
    return locked(runId, () -> file(runId).load());
  }

  private VersionedJsonFile<RunLease> file(String runId) {
    validate(runId);
    return new VersionedJsonFile<>(
        directory.resolve(runId + ".json"),
        mapper,
        mapper.getTypeFactory().constructType(RunLease.class));
  }

  private <T> T locked(String runId, Supplier<T> operation) {
    validate(runId);
    var lockPath = directory.resolve(runId + ".lock");
    var local = JVM_LOCKS.computeIfAbsent(lockPath, ignored -> new Object());
    synchronized (local) {
      try (var channel =
              FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
          var ignored = channel.lock()) {
        return operation.get();
      } catch (IOException exception) {
        throw new IllegalStateException("unable to lock run lease", exception);
      }
    }
  }

  private static void validate(String runId) {
    if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")) {
      throw new IllegalArgumentException("run id is invalid");
    }
  }
}

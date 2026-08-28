package io.legacypilot.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.state.VersionedJsonFile;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class FileCapabilityGrantStore implements CapabilityGrantStore {
  private static final java.util.concurrent.ConcurrentHashMap<Path, Object> JVM_LOCKS =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final Path file;
  private final Path lockFile;
  private final ObjectMapper mapper;

  public FileCapabilityGrantStore(Path file, ObjectMapper mapper) {
    this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
    this.lockFile =
        this.file.resolveSibling(Objects.requireNonNull(this.file.getFileName()) + ".lock");
    this.mapper = Objects.requireNonNull(mapper);
    try {
      Files.createDirectories(Objects.requireNonNull(this.file.getParent()));
    } catch (IOException exception) {
      throw new IllegalArgumentException("capability state directory is unavailable", exception);
    }
  }

  @Override
  public void save(CapabilityGrant grant) {
    locked(
        () -> {
          var values = new ArrayList<>(read());
          values.removeIf(existing -> existing.id().equals(grant.id()));
          values.add(grant);
          state().save(values);
          return null;
        });
  }

  @Override
  public Optional<CapabilityGrant> consume(String tokenDigest, CapabilityUse use, Instant now) {
    return locked(
        () -> {
          var values = new ArrayList<>(read());
          var index =
              java.util.stream.IntStream.range(0, values.size())
                  .filter(
                      candidate -> {
                        var grant = values.get(candidate);
                        return grant.tokenDigest().equals(tokenDigest) && grant.matches(use, now);
                      })
                  .findFirst();
          if (index.isEmpty()) {
            return Optional.empty();
          }
          var consumed = values.get(index.getAsInt()).consumed(now);
          values.set(index.getAsInt(), consumed);
          state().save(values);
          return Optional.of(consumed);
        });
  }

  @Override
  public Optional<CapabilityGrant> revoke(String id, Instant now) {
    return locked(
        () -> {
          var values = new ArrayList<>(read());
          for (var index = 0; index < values.size(); index++) {
            if (values.get(index).id().equals(id)) {
              var revoked = values.get(index).revoke(now);
              values.set(index, revoked);
              state().save(values);
              return Optional.of(revoked);
            }
          }
          return Optional.empty();
        });
  }

  @Override
  public Optional<CapabilityGrant> find(String id) {
    return locked(() -> read().stream().filter(grant -> grant.id().equals(id)).findFirst());
  }

  @Override
  public List<CapabilityGrant> list() {
    return locked(() -> List.copyOf(read()));
  }

  @Override
  public int purgeExpired(Instant now) {
    return locked(
        () -> {
          var values = new ArrayList<>(read());
          var before = values.size();
          values.removeIf(grant -> !now.isBefore(grant.expiresAt()) || grant.revoked());
          if (values.size() != before) {
            state().save(values);
          }
          return before - values.size();
        });
  }

  private List<CapabilityGrant> read() {
    return state().load().orElseGet(List::of);
  }

  private VersionedJsonFile<List<CapabilityGrant>> state() {
    var type = mapper.getTypeFactory().constructCollectionType(List.class, CapabilityGrant.class);
    return new VersionedJsonFile<>(file, mapper, type);
  }

  private <T> T locked(Supplier<T> operation) {
    var local = JVM_LOCKS.computeIfAbsent(lockFile, ignored -> new Object());
    synchronized (local) {
      try (var channel =
              FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
          var ignored = channel.lock()) {
        return operation.get();
      } catch (IOException exception) {
        throw new IllegalStateException("unable to lock capability state", exception);
      }
    }
  }
}

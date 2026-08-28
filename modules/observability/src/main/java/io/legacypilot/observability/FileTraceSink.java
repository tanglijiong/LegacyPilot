package io.legacypilot.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class FileTraceSink implements TraceSink {
  private static final java.util.concurrent.ConcurrentHashMap<Path, Object> JVM_LOCKS =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final Path directory;
  private final ObjectMapper mapper;
  private final SensitiveDataRedactor redactor;

  public FileTraceSink(Path directory, ObjectMapper mapper, SensitiveDataRedactor redactor) {
    this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
    this.mapper = Objects.requireNonNull(mapper);
    this.redactor = Objects.requireNonNull(redactor);
    try {
      Files.createDirectories(this.directory);
    } catch (IOException exception) {
      throw new IllegalArgumentException("trace directory is unavailable", exception);
    }
  }

  @Override
  public void append(TraceEvent event) {
    locked(
        event.runId(),
        () -> {
          var expected =
              readUnlocked(event.runId()).stream().mapToInt(TraceEvent::sequence).max().orElse(0)
                  + 1;
          if (event.sequence() != expected) {
            throw new IllegalStateException("trace sequence must be contiguous");
          }
          appendUnlocked(safe(event));
          return null;
        });
  }

  @Override
  public TraceEvent record(
      String runId, String type, Instant occurredAt, Map<String, String> attributes) {
    return locked(
        runId,
        () -> {
          var sequence =
              readUnlocked(runId).stream().mapToInt(TraceEvent::sequence).max().orElse(0) + 1;
          var event = safe(new TraceEvent(runId, sequence, type, occurredAt, attributes));
          appendUnlocked(event);
          return event;
        });
  }

  @Override
  public List<TraceEvent> events(String runId) {
    return locked(runId, () -> List.copyOf(readUnlocked(runId)));
  }

  private TraceEvent safe(TraceEvent event) {
    var attributes = new java.util.LinkedHashMap<String, String>();
    event.attributes().forEach((key, value) -> attributes.put(key, redactor.redact(key, value)));
    return new TraceEvent(
        event.runId(), event.sequence(), event.type(), event.occurredAt(), attributes);
  }

  private void appendUnlocked(TraceEvent event) {
    try {
      var line = mapper.writeValueAsString(event) + System.lineSeparator();
      Files.writeString(
          path(event.runId()),
          line,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException exception) {
      throw new IllegalStateException("unable to append trace", exception);
    }
  }

  private List<TraceEvent> readUnlocked(String runId) {
    var path = path(runId);
    if (!Files.exists(path)) {
      return List.of();
    }
    try {
      var values = new ArrayList<TraceEvent>();
      var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
      for (var index = 0; index < lines.size(); index++) {
        var line = lines.get(index);
        if (line.isBlank()) {
          continue;
        }
        try {
          values.add(mapper.readValue(line, TraceEvent.class));
        } catch (IOException exception) {
          if (index == lines.size() - 1) {
            Files.writeString(
                path.resolveSibling(path.getFileName() + ".corrupt-tail"),
                line,
                StandardCharsets.UTF_8);
            break;
          }
          throw exception;
        }
      }
      values.sort(Comparator.comparingInt(TraceEvent::sequence));
      return values;
    } catch (IOException exception) {
      throw new IllegalStateException("unable to read trace", exception);
    }
  }

  private <T> T locked(String runId, java.util.function.Supplier<T> operation) {
    validate(runId);
    var lockPath = directory.resolve(runId + ".lock");
    var local = JVM_LOCKS.computeIfAbsent(lockPath, ignored -> new Object());
    synchronized (local) {
      try (var channel =
              FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
          var ignored = channel.lock()) {
        return operation.get();
      } catch (IOException exception) {
        throw new IllegalStateException("unable to lock trace", exception);
      }
    }
  }

  private Path path(String runId) {
    validate(runId);
    return directory.resolve(runId + ".jsonl");
  }

  private static void validate(String runId) {
    if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")) {
      throw new IllegalArgumentException("run id is invalid");
    }
  }
}

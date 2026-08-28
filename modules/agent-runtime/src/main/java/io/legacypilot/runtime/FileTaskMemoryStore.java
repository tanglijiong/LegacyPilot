package io.legacypilot.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.context.TaskMemory;
import io.legacypilot.context.TaskMemoryStore;
import io.legacypilot.state.VersionedJsonFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class FileTaskMemoryStore implements TaskMemoryStore {
  private final Path directory;
  private final ObjectMapper mapper;
  private final int maximumEntries;

  public FileTaskMemoryStore(Path directory, ObjectMapper mapper, int maximumEntries) {
    this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
    this.mapper = Objects.requireNonNull(mapper);
    if (maximumEntries < 1) {
      throw new IllegalArgumentException("maximum entries must be positive");
    }
    this.maximumEntries = maximumEntries;
  }

  @Override
  public synchronized void append(TaskMemory memory) {
    var values = new ArrayList<>(read(memory.taskId()));
    values.removeIf(value -> value.id().equals(memory.id()));
    values.add(memory);
    values.sort(Comparator.comparing(TaskMemory::createdAt).thenComparing(TaskMemory::id));
    while (values.size() > maximumEntries) {
      values.removeFirst();
    }
    file(memory.taskId()).save(values);
  }

  @Override
  public synchronized List<TaskMemory> active(String taskId, Instant now) {
    return read(taskId).stream().filter(value -> value.activeAt(now)).toList();
  }

  @Override
  public synchronized void delete(String taskId) {
    try {
      var path = file(taskId).path();
      Files.deleteIfExists(path);
      Files.deleteIfExists(path.resolveSibling(path.getFileName() + ".previous"));
      Files.deleteIfExists(path.resolveSibling(path.getFileName() + ".v1.bak"));
      Files.deleteIfExists(path.resolveSibling(path.getFileName() + ".corrupt"));
    } catch (IOException exception) {
      throw new IllegalStateException("unable to delete task memory", exception);
    }
  }

  private List<TaskMemory> read(String taskId) {
    return file(taskId).load().orElseGet(List::of);
  }

  private VersionedJsonFile<List<TaskMemory>> file(String taskId) {
    if (taskId == null || !taskId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")) {
      throw new IllegalArgumentException("task id is invalid");
    }
    var type = mapper.getTypeFactory().constructCollectionType(List.class, TaskMemory.class);
    return new VersionedJsonFile<>(directory.resolve(taskId + ".json"), mapper, type);
  }
}

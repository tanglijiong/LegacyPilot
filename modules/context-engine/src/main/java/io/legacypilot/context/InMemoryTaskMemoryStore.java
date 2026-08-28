package io.legacypilot.context;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryTaskMemoryStore implements TaskMemoryStore {
  private final List<TaskMemory> values = new CopyOnWriteArrayList<>();
  private final int maximumEntries;

  public InMemoryTaskMemoryStore(int maximumEntries) {
    if (maximumEntries < 1) {
      throw new IllegalArgumentException("maximum entries must be positive");
    }
    this.maximumEntries = maximumEntries;
  }

  @Override
  public synchronized void append(TaskMemory memory) {
    values.removeIf(value -> value.id().equals(memory.id()));
    values.add(memory);
    while (values.size() > maximumEntries) {
      values.removeFirst();
    }
  }

  @Override
  public List<TaskMemory> active(String taskId, Instant now) {
    return values.stream()
        .filter(value -> value.taskId().equals(taskId) && value.activeAt(now))
        .sorted(Comparator.comparing(TaskMemory::createdAt).thenComparing(TaskMemory::id))
        .toList();
  }

  @Override
  public void delete(String taskId) {
    values.removeIf(value -> value.taskId().equals(taskId));
  }
}

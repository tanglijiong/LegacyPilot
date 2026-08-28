package io.legacypilot.context;

import java.time.Instant;
import java.util.List;

public interface TaskMemoryStore {
  void append(TaskMemory memory);

  List<TaskMemory> active(String taskId, Instant now);

  void delete(String taskId);
}

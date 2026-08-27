package io.legacypilot.application.port;

import io.legacypilot.domain.task.Task;
import io.legacypilot.domain.task.TaskId;
import java.util.Optional;

public interface TaskRepository {
  void add(Task task);

  Optional<Task> findById(TaskId id);
}

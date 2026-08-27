package io.legacypilot.application.service;

import io.legacypilot.application.error.ResourceNotFoundException;
import io.legacypilot.application.port.TaskRepository;
import io.legacypilot.domain.task.Task;
import io.legacypilot.domain.task.TaskId;
import java.util.Objects;

public final class GetTaskUseCase {

  private final TaskRepository tasks;

  public GetTaskUseCase(TaskRepository tasks) {
    this.tasks = Objects.requireNonNull(tasks);
  }

  public Task get(String taskId) {
    return tasks
        .findById(new TaskId(taskId))
        .orElseThrow(() -> new ResourceNotFoundException("Task", taskId));
  }
}

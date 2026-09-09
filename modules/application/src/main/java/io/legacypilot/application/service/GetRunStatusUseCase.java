package io.legacypilot.application.service;

import io.legacypilot.application.error.ResourceNotFoundException;
import io.legacypilot.application.port.TaskRunRepository;
import io.legacypilot.domain.run.RunId;
import io.legacypilot.domain.run.TaskRun;
import io.legacypilot.domain.task.TaskId;
import java.util.Objects;
import java.util.Optional;

public final class GetRunStatusUseCase {

  private final TaskRunRepository runs;

  public GetRunStatusUseCase(TaskRunRepository runs) {
    this.runs = Objects.requireNonNull(runs);
  }

  public TaskRun get(String runId) {
    return runs.findById(new RunId(runId))
        .orElseThrow(() -> new ResourceNotFoundException("TaskRun", runId));
  }

  public Optional<TaskRun> latestForTask(String taskId) {
    return runs.findLatestByTaskId(new TaskId(taskId));
  }
}

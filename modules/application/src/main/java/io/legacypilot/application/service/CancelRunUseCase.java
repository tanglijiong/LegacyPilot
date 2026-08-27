package io.legacypilot.application.service;

import io.legacypilot.application.error.ResourceNotFoundException;
import io.legacypilot.application.port.ProjectRepository;
import io.legacypilot.application.port.TaskRepository;
import io.legacypilot.application.port.TaskRunRepository;
import io.legacypilot.application.port.WorkspaceService;
import io.legacypilot.domain.run.RunId;
import io.legacypilot.domain.run.RunStatus;
import java.time.Clock;
import java.util.Objects;

public final class CancelRunUseCase {

  private final ProjectRepository projects;
  private final TaskRepository tasks;
  private final TaskRunRepository runs;
  private final WorkspaceService workspaces;
  private final Clock clock;

  public CancelRunUseCase(
      ProjectRepository projects,
      TaskRepository tasks,
      TaskRunRepository runs,
      WorkspaceService workspaces,
      Clock clock) {
    this.projects = Objects.requireNonNull(projects);
    this.tasks = Objects.requireNonNull(tasks);
    this.runs = Objects.requireNonNull(runs);
    this.workspaces = Objects.requireNonNull(workspaces);
    this.clock = Objects.requireNonNull(clock);
  }

  public io.legacypilot.domain.run.TaskRun cancel(String runIdValue) {
    var runId = new RunId(runIdValue);
    var run =
        runs.findById(runId)
            .orElseThrow(() -> new ResourceNotFoundException("TaskRun", runIdValue));
    var task =
        tasks
            .findById(run.taskId())
            .orElseThrow(() -> new ResourceNotFoundException("Task", run.taskId().value()));
    var project =
        projects
            .findById(task.projectId())
            .orElseThrow(() -> new ResourceNotFoundException("Project", task.projectId().value()));

    if (run.workspaceId() != null) {
      workspaces.cleanup(project, run.id(), run.workspaceId());
    }
    return runs.update(run.transitionTo(RunStatus.CANCELLED, clock.instant(), "cancelled by user"));
  }
}

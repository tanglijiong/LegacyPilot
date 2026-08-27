package io.legacypilot.application.service;

import io.legacypilot.application.error.ResourceNotFoundException;
import io.legacypilot.application.port.IdGenerator;
import io.legacypilot.application.port.ProjectRepository;
import io.legacypilot.application.port.TaskRepository;
import io.legacypilot.application.port.TaskRunRepository;
import io.legacypilot.application.port.WorkspaceService;
import io.legacypilot.domain.run.RunId;
import io.legacypilot.domain.run.RunStatus;
import io.legacypilot.domain.run.TaskRun;
import io.legacypilot.domain.task.TaskId;
import java.time.Clock;
import java.util.Objects;

public final class StartRunUseCase {

  private final ProjectRepository projects;
  private final TaskRepository tasks;
  private final TaskRunRepository runs;
  private final WorkspaceService workspaces;
  private final IdGenerator ids;
  private final Clock clock;

  public StartRunUseCase(
      ProjectRepository projects,
      TaskRepository tasks,
      TaskRunRepository runs,
      WorkspaceService workspaces,
      IdGenerator ids,
      Clock clock) {
    this.projects = Objects.requireNonNull(projects);
    this.tasks = Objects.requireNonNull(tasks);
    this.runs = Objects.requireNonNull(runs);
    this.workspaces = Objects.requireNonNull(workspaces);
    this.ids = Objects.requireNonNull(ids);
    this.clock = Objects.requireNonNull(clock);
  }

  public TaskRun start(String taskIdValue) {
    var taskId = new TaskId(taskIdValue);
    var task =
        tasks
            .findById(taskId)
            .orElseThrow(() -> new ResourceNotFoundException("Task", taskIdValue));
    var project =
        projects
            .findById(task.projectId())
            .orElseThrow(() -> new ResourceNotFoundException("Project", task.projectId().value()));

    var run = runs.add(TaskRun.create(new RunId(ids.next("run")), taskId, clock.instant()));
    run =
        runs.update(
            run.transitionTo(RunStatus.PREPARING_WORKSPACE, clock.instant(), "run started"));
    try {
      var workspace = workspaces.create(project, run.id());
      return runs.update(run.workspaceReady(workspace.id(), clock.instant()));
    } catch (RuntimeException exception) {
      runs.update(run.transitionTo(RunStatus.FAILED, clock.instant(), exception.getMessage()));
      throw exception;
    }
  }
}

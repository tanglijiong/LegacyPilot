package io.legacypilot.application.service;

import io.legacypilot.application.error.ResourceNotFoundException;
import io.legacypilot.application.port.IdGenerator;
import io.legacypilot.application.port.ProjectRepository;
import io.legacypilot.application.port.TaskRepository;
import io.legacypilot.domain.project.ProjectId;
import io.legacypilot.domain.task.AcceptanceCriteria;
import io.legacypilot.domain.task.Requirement;
import io.legacypilot.domain.task.Task;
import io.legacypilot.domain.task.TaskId;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

public final class CreateTaskUseCase {

  private final ProjectRepository projects;
  private final TaskRepository tasks;
  private final IdGenerator ids;
  private final Clock clock;

  public CreateTaskUseCase(
      ProjectRepository projects, TaskRepository tasks, IdGenerator ids, Clock clock) {
    this.projects = Objects.requireNonNull(projects);
    this.tasks = Objects.requireNonNull(tasks);
    this.ids = Objects.requireNonNull(ids);
    this.clock = Objects.requireNonNull(clock);
  }

  public Task create(String projectIdValue, String requirement, List<String> criteria) {
    var projectId = new ProjectId(projectIdValue);
    if (projects.findById(projectId).isEmpty()) {
      throw new ResourceNotFoundException("Project", projectIdValue);
    }
    var task =
        new Task(
            new TaskId(ids.next("task")),
            projectId,
            new Requirement(requirement),
            new AcceptanceCriteria(criteria == null ? List.of() : criteria),
            clock.instant());
    tasks.add(task);
    return task;
  }
}

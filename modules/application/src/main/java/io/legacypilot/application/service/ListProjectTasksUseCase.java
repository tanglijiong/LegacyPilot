package io.legacypilot.application.service;

import io.legacypilot.application.error.ResourceNotFoundException;
import io.legacypilot.application.port.ProjectRepository;
import io.legacypilot.application.port.TaskRepository;
import io.legacypilot.domain.project.ProjectId;
import io.legacypilot.domain.task.Task;
import java.util.List;
import java.util.Objects;

public final class ListProjectTasksUseCase {
  private final ProjectRepository projects;
  private final TaskRepository tasks;

  public ListProjectTasksUseCase(ProjectRepository projects, TaskRepository tasks) {
    this.projects = Objects.requireNonNull(projects);
    this.tasks = Objects.requireNonNull(tasks);
  }

  public List<Task> list(String projectIdValue) {
    var projectId = new ProjectId(projectIdValue);
    if (projects.findById(projectId).isEmpty()) {
      throw new ResourceNotFoundException("Project", projectIdValue);
    }
    return tasks.findByProjectId(projectId);
  }
}

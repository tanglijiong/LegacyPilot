package io.legacypilot.application.service;

import io.legacypilot.application.error.ResourceNotFoundException;
import io.legacypilot.application.port.ProjectRepository;
import io.legacypilot.domain.project.ProjectId;
import java.util.Objects;

public final class RemoveProjectUseCase {

  private final ProjectRepository projects;

  public RemoveProjectUseCase(ProjectRepository projects) {
    this.projects = Objects.requireNonNull(projects);
  }

  public void remove(String projectId) {
    var id = new ProjectId(projectId);
    if (projects.findById(id).isEmpty()) {
      throw new ResourceNotFoundException("Project", projectId);
    }
    projects.remove(id);
  }
}

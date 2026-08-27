package io.legacypilot.application.service;

import io.legacypilot.application.error.ResourceNotFoundException;
import io.legacypilot.application.port.ProjectRepository;
import io.legacypilot.domain.project.Project;
import io.legacypilot.domain.project.ProjectId;
import java.util.Objects;

public final class GetProjectUseCase {

  private final ProjectRepository projects;

  public GetProjectUseCase(ProjectRepository projects) {
    this.projects = Objects.requireNonNull(projects);
  }

  public Project get(String projectId) {
    return projects
        .findById(new ProjectId(projectId))
        .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
  }
}

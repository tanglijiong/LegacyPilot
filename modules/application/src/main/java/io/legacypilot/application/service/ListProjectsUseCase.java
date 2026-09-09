package io.legacypilot.application.service;

import io.legacypilot.application.port.ProjectRepository;
import io.legacypilot.domain.project.Project;
import java.util.List;
import java.util.Objects;

public final class ListProjectsUseCase {
  private final ProjectRepository projects;

  public ListProjectsUseCase(ProjectRepository projects) {
    this.projects = Objects.requireNonNull(projects);
  }

  public List<Project> list() {
    return projects.findAll();
  }
}

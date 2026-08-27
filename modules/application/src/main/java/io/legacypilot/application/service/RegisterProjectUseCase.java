package io.legacypilot.application.service;

import io.legacypilot.application.port.IdGenerator;
import io.legacypilot.application.port.ProjectRepository;
import io.legacypilot.application.port.WorkspaceService;
import io.legacypilot.domain.project.Project;
import io.legacypilot.domain.project.ProjectId;
import io.legacypilot.domain.project.RepositoryLocation;
import java.time.Clock;
import java.util.Objects;

public final class RegisterProjectUseCase {

  private final ProjectRepository projects;
  private final WorkspaceService workspaces;
  private final IdGenerator ids;
  private final Clock clock;

  public RegisterProjectUseCase(
      ProjectRepository projects, WorkspaceService workspaces, IdGenerator ids, Clock clock) {
    this.projects = Objects.requireNonNull(projects);
    this.workspaces = Objects.requireNonNull(workspaces);
    this.ids = Objects.requireNonNull(ids);
    this.clock = Objects.requireNonNull(clock);
  }

  public Project register(String source, String requestedRevision) {
    var location = new RepositoryLocation(source);
    var registered = workspaces.register(location, requestedRevision);
    var project =
        new Project(
            new ProjectId(ids.next("project")),
            location,
            registered.repositoryPath(),
            registered.revision(),
            clock.instant());
    projects.add(project);
    return project;
  }
}

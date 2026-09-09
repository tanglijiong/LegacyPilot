package io.legacypilot.application.service;

import io.legacypilot.application.error.ResourceNotFoundException;
import io.legacypilot.application.error.SourceAccessException;
import io.legacypilot.application.port.ProjectRepository;
import io.legacypilot.application.port.SourceFileReader;
import io.legacypilot.application.port.SourceFileReader.SourceReadRequest;
import io.legacypilot.application.port.SourceFileReader.SourceSnippet;
import io.legacypilot.domain.project.ProjectId;
import java.util.Objects;

public final class ReadProjectSourceUseCase {

  private final ProjectRepository projects;
  private final SourceFileReader sourceFiles;

  public ReadProjectSourceUseCase(ProjectRepository projects, SourceFileReader sourceFiles) {
    this.projects = Objects.requireNonNull(projects);
    this.sourceFiles = Objects.requireNonNull(sourceFiles);
  }

  public SourceSnippet read(String projectId, SourceReadRequest request) {
    Objects.requireNonNull(request, "source read request must not be null");
    var project =
        projects
            .findById(new ProjectId(projectId))
            .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
    if (!project.baseRevision().value().equalsIgnoreCase(request.revision())) {
      throw new SourceAccessException(
          SourceAccessException.Reason.REVISION_MISMATCH,
          "Requested revision does not match the registered project revision");
    }
    return sourceFiles.read(project, request);
  }
}

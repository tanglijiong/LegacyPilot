package io.legacypilot.application.service;

import io.legacypilot.application.port.DirectorySnapshotFile;
import io.legacypilot.application.port.RepositoryInspection;
import io.legacypilot.application.port.WorkspaceService;
import io.legacypilot.domain.project.RepositoryLocation;
import java.util.List;
import java.util.Objects;

public final class InspectProjectUseCase {

  private final WorkspaceService workspaces;

  public InspectProjectUseCase(WorkspaceService workspaces) {
    this.workspaces = Objects.requireNonNull(workspaces);
  }

  public RepositoryInspection inspect(String source, String requestedRevision) {
    return workspaces.inspect(new RepositoryLocation(source), requestedRevision);
  }

  public RepositoryInspection inspectDirectory(
      String directoryName, List<DirectorySnapshotFile> files) {
    return workspaces.inspectDirectory(directoryName, files);
  }
}

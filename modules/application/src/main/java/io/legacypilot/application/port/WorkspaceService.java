package io.legacypilot.application.port;

import io.legacypilot.domain.project.Project;
import io.legacypilot.domain.project.RepositoryLocation;
import io.legacypilot.domain.run.RunId;
import io.legacypilot.domain.run.WorkspaceId;
import java.util.List;

public interface WorkspaceService {
  RepositoryInspection inspect(RepositoryLocation location, String requestedRevision);

  RepositoryInspection inspectDirectory(String directoryName, List<DirectorySnapshotFile> files);

  RegisteredRepository register(RepositoryLocation location, String requestedRevision);

  WorkspaceDescriptor create(Project project, RunId runId);

  WorkspaceDescriptor open(Project project, RunId runId, WorkspaceId workspaceId);

  void cleanup(Project project, RunId runId, WorkspaceId workspaceId);
}

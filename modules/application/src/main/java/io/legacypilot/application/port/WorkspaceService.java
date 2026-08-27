package io.legacypilot.application.port;

import io.legacypilot.domain.project.Project;
import io.legacypilot.domain.project.RepositoryLocation;
import io.legacypilot.domain.run.RunId;
import io.legacypilot.domain.run.WorkspaceId;

public interface WorkspaceService {
  RegisteredRepository register(RepositoryLocation location, String requestedRevision);

  WorkspaceDescriptor create(Project project, RunId runId);

  void cleanup(Project project, RunId runId, WorkspaceId workspaceId);
}

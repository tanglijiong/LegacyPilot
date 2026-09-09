package io.legacypilot.runtime;

import io.legacypilot.analysis.java.ProjectIndex;
import io.legacypilot.context.ContextRequest;
import io.legacypilot.domain.task.TaskMode;
import java.nio.file.Path;
import java.util.Objects;

public record AgentRunRequest(
    String runId,
    String requirement,
    TaskMode mode,
    Path workspace,
    ProjectIndex projectIndex,
    ContextRequest contextRequest,
    RuntimeBudget budget,
    String model) {

  public AgentRunRequest {
    Objects.requireNonNull(runId);
    Objects.requireNonNull(requirement);
    mode = mode == null ? TaskMode.CHANGE : mode;
    workspace = Objects.requireNonNull(workspace).toAbsolutePath().normalize();
    Objects.requireNonNull(projectIndex);
    Objects.requireNonNull(contextRequest);
    Objects.requireNonNull(budget);
    Objects.requireNonNull(model);
    if (runId.isBlank() || requirement.isBlank() || model.isBlank()) {
      throw new IllegalArgumentException("agent run request is invalid");
    }
  }

  public AgentRunRequest(
      String runId,
      String requirement,
      Path workspace,
      ProjectIndex projectIndex,
      ContextRequest contextRequest,
      RuntimeBudget budget,
      String model) {
    this(
        runId,
        requirement,
        TaskMode.CHANGE,
        workspace,
        projectIndex,
        contextRequest,
        budget,
        model);
  }
}

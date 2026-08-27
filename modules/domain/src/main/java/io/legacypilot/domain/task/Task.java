package io.legacypilot.domain.task;

import io.legacypilot.domain.project.ProjectId;
import java.time.Instant;
import java.util.Objects;

public record Task(
    TaskId id,
    ProjectId projectId,
    Requirement requirement,
    AcceptanceCriteria acceptanceCriteria,
    Instant createdAt) {

  public Task {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(projectId, "projectId must not be null");
    Objects.requireNonNull(requirement, "requirement must not be null");
    Objects.requireNonNull(acceptanceCriteria, "acceptanceCriteria must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
  }
}

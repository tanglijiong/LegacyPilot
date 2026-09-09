package io.legacypilot.domain.task;

import io.legacypilot.domain.project.ProjectId;
import java.time.Instant;
import java.util.Objects;

public record Task(
    TaskId id,
    ProjectId projectId,
    String conversationId,
    TaskMode mode,
    Requirement requirement,
    AcceptanceCriteria acceptanceCriteria,
    Instant createdAt) {

  public Task {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(projectId, "projectId must not be null");
    conversationId = Objects.requireNonNull(conversationId, "conversationId must not be null");
    if (conversationId.isBlank()) {
      throw new IllegalArgumentException("conversationId must not be blank");
    }
    mode = mode == null ? TaskMode.CHANGE : mode;
    Objects.requireNonNull(requirement, "requirement must not be null");
    Objects.requireNonNull(acceptanceCriteria, "acceptanceCriteria must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
  }

  /** Backward-compatible constructor for callers that create a standalone task. */
  public Task(
      TaskId id,
      ProjectId projectId,
      TaskMode mode,
      Requirement requirement,
      AcceptanceCriteria acceptanceCriteria,
      Instant createdAt) {
    this(id, projectId, id.value(), mode, requirement, acceptanceCriteria, createdAt);
  }

  /** Backward-compatible constructor for callers created before task modes were introduced. */
  public Task(
      TaskId id,
      ProjectId projectId,
      Requirement requirement,
      AcceptanceCriteria acceptanceCriteria,
      Instant createdAt) {
    this(id, projectId, id.value(), TaskMode.CHANGE, requirement, acceptanceCriteria, createdAt);
  }
}

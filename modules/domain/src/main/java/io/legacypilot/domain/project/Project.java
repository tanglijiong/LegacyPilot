package io.legacypilot.domain.project;

import java.time.Instant;
import java.util.Objects;

/** A source repository registered at an immutable base revision. */
public record Project(
    ProjectId id,
    RepositoryLocation source,
    String repositoryPath,
    GitRevision baseRevision,
    Instant registeredAt) {

  public Project {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(source, "source must not be null");
    Objects.requireNonNull(repositoryPath, "repositoryPath must not be null");
    Objects.requireNonNull(baseRevision, "baseRevision must not be null");
    Objects.requireNonNull(registeredAt, "registeredAt must not be null");
    if (repositoryPath.isBlank()) {
      throw new IllegalArgumentException("repositoryPath must not be blank");
    }
  }
}

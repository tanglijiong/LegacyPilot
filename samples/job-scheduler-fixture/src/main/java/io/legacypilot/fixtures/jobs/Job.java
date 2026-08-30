package io.legacypilot.fixtures.jobs;

import java.time.Instant;

public record Job(String id, Instant scheduledAt, int attempt, int priority, JobStatus status) {
  public Job withStatus(JobStatus newStatus) {
    return new Job(id, scheduledAt, attempt, priority, newStatus);
  }

  public Job retryAt(Instant nextAttemptAt) {
    return new Job(id, nextAttemptAt, attempt + 1, priority, JobStatus.READY);
  }
}

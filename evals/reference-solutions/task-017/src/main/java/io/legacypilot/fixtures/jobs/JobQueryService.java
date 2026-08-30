package io.legacypilot.fixtures.jobs;

import java.time.Instant;
import java.util.List;

public final class JobQueryService {
  private final JobRepository repository;

  public JobQueryService(JobRepository repository) {
    this.repository = repository;
  }

  public List<Job> dueBefore(Instant dueExclusive, int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("INVALID_BATCH_LIMIT");
    }
    return repository.findReadyScheduledBefore(dueExclusive, limit);
  }

  public List<Job> page(List<Job> jobs, int offset, int limit) {
    var end = Math.min(jobs.size(), offset + limit);
    return jobs.subList(offset, end);
  }
}

package io.legacypilot.fixtures.jobs;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public final class JobScheduler {
  private final JobRepository repository;
  private final RetryPolicy retryPolicy;

  public JobScheduler(JobRepository repository, RetryPolicy retryPolicy) {
    this.repository = repository;
    this.retryPolicy = retryPolicy;
  }

  public Job retry(Job job, Instant now) {
    var retried = job.retryAt(retryPolicy.nextAttempt(now, job.attempt()));
    repository.save(retried);
    return retried;
  }

  public Job schedule(String id, LocalDateTime scheduledAt, int priority) {
    var job =
        new Job(id, scheduledAt.toInstant(ZoneOffset.UTC), 0, priority, JobStatus.READY);
    repository.save(job);
    return job;
  }

  public boolean claim(String id) {
    var job = repository.findById(id).orElse(null);
    if (job == null || job.status() != JobStatus.READY) {
      return false;
    }
    repository.save(job.withStatus(JobStatus.RUNNING));
    return true;
  }
}

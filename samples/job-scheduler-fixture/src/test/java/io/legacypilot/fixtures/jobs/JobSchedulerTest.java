package io.legacypilot.fixtures.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JobSchedulerTest {
  @Test
  void retriesAJob() {
    var repository = new MemoryRepository();
    var scheduler = new JobScheduler(repository, new RetryPolicy());
    var job = new Job("job-1", Instant.EPOCH, 0, 3, JobStatus.RUNNING);

    var retried = scheduler.retry(job, Instant.EPOCH);

    assertEquals(1, retried.attempt());
    assertEquals(JobStatus.READY, retried.status());
  }

  private static final class MemoryRepository implements JobRepository {
    private final Map<String, Job> jobs = new HashMap<>();

    @Override
    public void save(Job job) {
      jobs.put(job.id(), job);
    }

    @Override
    public Optional<Job> findById(String id) {
      return Optional.ofNullable(jobs.get(id));
    }
  }
}

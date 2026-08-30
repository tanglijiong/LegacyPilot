package io.legacypilot.fixtures.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class Task013HiddenTest {
  @Test
  void serializesConcurrentClaimsSoOnlyOneWins() throws Exception {
    var method = JobScheduler.class.getDeclaredMethod("claim", String.class);
    assertTrue(Modifier.isSynchronized(method.getModifiers()));

    var repository = new MemoryRepository();
    repository.job = new Job("j1", Instant.EPOCH, 0, 3, JobStatus.READY);
    var scheduler = new JobScheduler(repository, new RetryPolicy());
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> scheduler.claim("j1"));
      var second = executor.submit(() -> scheduler.claim("j1"));
      var wins = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
      assertEquals(1, wins);
    }
  }

  private static final class MemoryRepository implements JobRepository {
    private Job job;

    public void save(Job value) {
      job = value;
    }

    public Optional<Job> findById(String id) {
      return Optional.ofNullable(job);
    }
  }
}

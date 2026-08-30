package io.legacypilot.fixtures.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class Task012HiddenTest {
  @Test
  void interpretsScheduleInputAsUtc() {
    var scheduler = new JobScheduler(new NoOpRepository(), new RetryPolicy());

    var job = scheduler.schedule("j1", LocalDateTime.of(2026, 8, 30, 9, 15), 3);

    assertEquals(Instant.parse("2026-08-30T09:15:00Z"), job.scheduledAt());
  }

  private static final class NoOpRepository implements JobRepository {
    public void save(Job job) {}

    public Optional<Job> findById(String id) {
      return Optional.empty();
    }
  }
}

package io.legacypilot.fixtures.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class Task015HiddenTest {
  @Test
  void pagesByScheduledTimeThenIdRegardlessOfInputOrder() {
    var sameTime = Instant.parse("2026-08-30T10:00:00Z");
    var jobs =
        List.of(
            job("c", sameTime.plusSeconds(60)), job("b", sameTime), job("a", sameTime));
    var service = new JobQueryService();

    assertEquals(List.of("a", "b"), service.page(jobs, 0, 2).stream().map(Job::id).toList());
    assertEquals(List.of("c"), service.page(jobs, 2, 2).stream().map(Job::id).toList());
    assertEquals(List.of(), service.page(jobs, 8, 2));
    assertThrows(IllegalArgumentException.class, () -> service.page(jobs, -1, 2));
  }

  private static Job job(String id, Instant scheduledAt) {
    return new Job(id, scheduledAt, 0, 3, JobStatus.READY);
  }
}

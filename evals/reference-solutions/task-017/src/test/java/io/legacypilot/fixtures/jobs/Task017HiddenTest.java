package io.legacypilot.fixtures.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class Task017HiddenTest {
  @Test
  void validatesAndDelegatesDueJobBatches() {
    var repository = new QueryRepository();
    var service = new JobQueryService(repository);
    var due = Instant.parse("2026-08-30T10:00:00Z");

    assertThrows(IllegalArgumentException.class, () -> service.dueBefore(due, 0));
    assertThrows(IllegalArgumentException.class, () -> service.dueBefore(due, 101));
    assertEquals(0, repository.queryCount);
    assertEquals(List.of(), service.dueBefore(due, 25));
    assertEquals(1, repository.queryCount);
    assertEquals(due, repository.dueExclusive);
    assertEquals(25, repository.limit);
  }

  private static final class QueryRepository implements JobRepository {
    private int queryCount;
    private Instant dueExclusive;
    private int limit;

    public void save(Job job) {}

    public Optional<Job> findById(String id) {
      return Optional.empty();
    }

    public List<Job> findReadyScheduledBefore(Instant dueExclusive, int limit) {
      queryCount++;
      this.dueExclusive = dueExclusive;
      this.limit = limit;
      return List.of();
    }
  }
}

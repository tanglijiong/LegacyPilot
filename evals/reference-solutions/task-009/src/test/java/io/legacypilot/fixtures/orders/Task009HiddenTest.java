package io.legacypilot.fixtures.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class Task009HiddenTest {
  @Test
  void validatesRangesBeforeQueryingRepository() {
    var repository = new HistoryRepository();
    var service = new OrderService(repository, Clock.systemUTC());
    var start = Instant.parse("2026-08-01T00:00:00Z");

    assertThrows(IllegalArgumentException.class, () -> service.history(start, start));
    assertEquals(0, repository.queryCount);
    assertEquals(List.of(), service.history(start, start.plusSeconds(60)));
    assertEquals(1, repository.queryCount);
  }

  private static final class HistoryRepository implements OrderRepository {
    private int queryCount;

    @Override
    public void save(Order order) {}

    @Override
    public Optional<Order> findById(String id) {
      return Optional.empty();
    }

    @Override
    public List<Order> findByCreatedAtBetween(Instant fromInclusive, Instant toExclusive) {
      queryCount++;
      return List.of();
    }
  }
}

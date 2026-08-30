package io.legacypilot.fixtures.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class Task016HiddenTest {
  @Test
  void cancellationEnforcesStateTransitionsAndIdempotency() {
    var repository = new MemoryRepository(order(OrderStatus.CREATED));
    var service = new OrderService(repository, Clock.systemUTC());

    var cancelled = service.cancel("o1");
    assertEquals(OrderStatus.CANCELLED, cancelled.status());
    assertEquals(1, repository.saveCount);
    assertSame(cancelled, service.cancel("o1"));
    assertEquals(1, repository.saveCount);

    repository.current = order(OrderStatus.COMPLETED);
    assertThrows(IllegalStateException.class, () -> service.cancel("o1"));
    assertEquals(1, repository.saveCount);
  }

  private static Order order(OrderStatus status) {
    return new Order("o1", "r1", "sku", 1, status, "token", Instant.EPOCH);
  }

  private static final class MemoryRepository implements OrderRepository {
    private Order current;
    private int saveCount;

    private MemoryRepository(Order current) {
      this.current = current;
    }

    public void save(Order order) {
      saveCount++;
      current = order;
    }

    public Optional<Order> findById(String id) {
      return Optional.ofNullable(current);
    }
  }
}

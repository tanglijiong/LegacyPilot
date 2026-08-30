package io.legacypilot.fixtures.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class Task006HiddenTest {
  @Test
  void repeatedRequestReturnsOriginalOrderWithoutAnotherWrite() {
    var repository = new MemoryRepository();
    var service = new OrderService(repository, Clock.systemUTC());

    var first = service.create("request-7", "sku-1", 2, "token");
    var repeated = service.create("request-7", "sku-1", 2, "token");

    assertSame(first, repeated);
    assertEquals(1, repository.saveCount);
  }

  private static final class MemoryRepository implements OrderRepository {
    private final Map<String, Order> orders = new HashMap<>();
    private int saveCount;

    @Override
    public void save(Order order) {
      saveCount++;
      orders.put(order.id(), order);
    }

    @Override
    public Optional<Order> findById(String id) {
      return Optional.ofNullable(orders.get(id));
    }

    @Override
    public Optional<Order> findByRequestId(String requestId) {
      return orders.values().stream().filter(order -> order.requestId().equals(requestId)).findFirst();
    }
  }
}

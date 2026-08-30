package io.legacypilot.fixtures.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OrderServiceTest {
  @Test
  void createsAndStoresAnOrder() {
    var repository = new MemoryRepository();
    var service =
        new OrderService(
            repository,
            Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC));

    var order = service.create("request-1", "sku-1", 2, "token-123");

    assertEquals("sku-1", order.sku());
    assertEquals(1, repository.orders.size());
  }

  private static final class MemoryRepository implements OrderRepository {
    private final List<Order> orders = new ArrayList<>();

    @Override
    public void save(Order order) {
      orders.add(order);
    }

    @Override
    public Optional<Order> findById(String id) {
      return orders.stream().filter(order -> order.id().equals(id)).findFirst();
    }
  }
}

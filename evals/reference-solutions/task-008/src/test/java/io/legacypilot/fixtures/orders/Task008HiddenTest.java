package io.legacypilot.fixtures.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class Task008HiddenTest {
  @Test
  void missingOrderMapsToStableNotFoundResponse() {
    OrderRepository repository =
        new OrderRepository() {
          public void save(Order order) {}

          public Optional<Order> findById(String id) {
            return Optional.empty();
          }
        };

    var response = new OrderController(new OrderService(repository, Clock.systemUTC())).get("missing");

    assertEquals(404, response.status());
    assertEquals("ORDER_NOT_FOUND", response.error());
  }
}

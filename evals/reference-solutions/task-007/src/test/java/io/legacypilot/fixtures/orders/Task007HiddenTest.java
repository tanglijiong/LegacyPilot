package io.legacypilot.fixtures.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class Task007HiddenTest {
  @Test
  void rejectsInvalidItemsBeforeSaving() {
    var repository = new CountingRepository();
    var service = new OrderService(repository, Clock.systemUTC());

    assertThrows(IllegalArgumentException.class, () -> service.create("r1", " ", 1, "t"));
    assertThrows(IllegalArgumentException.class, () -> service.create("r2", "sku", 0, "t"));
    assertEquals(0, repository.saveCount);
  }

  private static final class CountingRepository implements OrderRepository {
    private int saveCount;

    @Override
    public void save(Order order) {
      saveCount++;
    }

    @Override
    public Optional<Order> findById(String id) {
      return Optional.empty();
    }
  }
}

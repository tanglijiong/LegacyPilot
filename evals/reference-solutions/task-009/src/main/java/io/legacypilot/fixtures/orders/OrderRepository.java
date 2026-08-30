package io.legacypilot.fixtures.orders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {
  void save(Order order);

  Optional<Order> findById(String id);

  default List<Order> findByCreatedAtBetween(Instant fromInclusive, Instant toExclusive) {
    return List.of();
  }
}

package io.legacypilot.fixtures.orders;

import java.util.Optional;

public interface OrderRepository {
  void save(Order order);

  Optional<Order> findById(String id);

  default Optional<Order> findByRequestId(String requestId) {
    return Optional.empty();
  }
}

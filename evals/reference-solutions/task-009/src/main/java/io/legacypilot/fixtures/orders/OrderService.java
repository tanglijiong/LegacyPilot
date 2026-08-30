package io.legacypilot.fixtures.orders;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class OrderService {
  private final OrderRepository repository;
  private final Clock clock;

  public OrderService(OrderRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  public Order create(String requestId, String sku, int quantity, String paymentToken) {
    var order =
        new Order(
            UUID.randomUUID().toString(),
            requestId,
            sku,
            quantity,
            OrderStatus.CREATED,
            paymentToken,
            clock.instant());
    repository.save(order);
    return order;
  }

  public Order get(String id) {
    return repository.findById(id).orElseThrow(IllegalArgumentException::new);
  }

  public Optional<Order> find(String id) {
    return repository.findById(id);
  }

  public List<Order> history(Instant fromInclusive, Instant toExclusive) {
    if (!toExclusive.isAfter(fromInclusive)) {
      throw new IllegalArgumentException("INVALID_HISTORY_RANGE");
    }
    return repository.findByCreatedAtBetween(fromInclusive, toExclusive);
  }
}

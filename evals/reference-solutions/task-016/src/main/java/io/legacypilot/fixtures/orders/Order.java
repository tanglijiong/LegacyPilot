package io.legacypilot.fixtures.orders;

import java.time.Instant;

public record Order(
    String id,
    String requestId,
    String sku,
    int quantity,
    OrderStatus status,
    String paymentToken,
    Instant createdAt) {
  public Order cancel() {
    if (status == OrderStatus.COMPLETED) {
      throw new IllegalStateException("ORDER_ALREADY_COMPLETED");
    }
    if (status == OrderStatus.CANCELLED) {
      return this;
    }
    return new Order(id, requestId, sku, quantity, OrderStatus.CANCELLED, paymentToken, createdAt);
  }
}

package io.legacypilot.fixtures.orders;

public final class OrderNotFoundException extends RuntimeException {
  public OrderNotFoundException(String id) {
    super("Order not found: " + id);
  }
}

package io.legacypilot.fixtures.orders;

public final class OrderAuditFormatter {
  public String format(Order order) {
    return "order=" + order.id() + ",paymentToken=[REDACTED]";
  }
}

package io.legacypilot.fixtures.orders;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class Task010HiddenTest {
  @Test
  void auditOutputNeverContainsPaymentCredential() {
    var order =
        new Order("o1", "r1", "sku", 1, OrderStatus.CREATED, "secret-token", Instant.EPOCH);

    var output = new OrderAuditFormatter().format(order);

    assertFalse(output.contains("secret-token"));
    assertTrue(output.contains("[REDACTED]"));
  }
}

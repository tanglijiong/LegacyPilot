package io.legacypilot.fixtures.orders;

import java.time.Instant;

public record Order(
    String id,
    String requestId,
    String sku,
    int quantity,
    OrderStatus status,
    String paymentToken,
    Instant createdAt) {}

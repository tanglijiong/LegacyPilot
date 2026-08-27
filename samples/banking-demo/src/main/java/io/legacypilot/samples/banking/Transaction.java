package io.legacypilot.samples.banking;

import java.math.BigDecimal;
import java.time.Instant;

public record Transaction(
    String id, String accountId, String type, BigDecimal amount, Instant occurredAt) {}

package io.legacypilot.samples.banking;

import java.math.BigDecimal;
import java.time.Instant;

public record TransferQuery(
    String accountId,
    BigDecimal totalAmount,
    int count,
    Instant from,
    Instant to) {}

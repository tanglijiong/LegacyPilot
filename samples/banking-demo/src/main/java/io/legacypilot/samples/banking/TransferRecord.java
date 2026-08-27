package io.legacypilot.samples.banking;

import java.math.BigDecimal;
import java.time.Instant;

public record TransferRecord(String id, String accountId, BigDecimal amount, Instant occurredAt) {}

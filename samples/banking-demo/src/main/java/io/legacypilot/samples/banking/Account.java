package io.legacypilot.samples.banking;

import java.math.BigDecimal;

public record Account(String id, String customerId, BigDecimal balance, String status) {}

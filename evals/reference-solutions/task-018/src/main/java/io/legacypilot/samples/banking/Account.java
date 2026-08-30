package io.legacypilot.samples.banking;

import java.math.BigDecimal;

public record Account(String id, String customerId, BigDecimal balance, String status) {
  public Account debit(BigDecimal amount) {
    if (amount == null || amount.signum() <= 0) {
      throw new IllegalArgumentException("INVALID_TRANSFER_AMOUNT");
    }
    if (balance.compareTo(amount) < 0) {
      throw new IllegalStateException("INSUFFICIENT_FUNDS");
    }
    return new Account(id, customerId, balance.subtract(amount), status);
  }
}

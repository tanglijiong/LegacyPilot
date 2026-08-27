package io.legacypilot.samples.banking;

import java.math.BigDecimal;

public final class DailyTransferPolicy {
  private static final BigDecimal STANDARD_LIMIT = new BigDecimal("50000");
  private static final BigDecimal VIP_LIMIT = new BigDecimal("200000");

  public BigDecimal limitFor(Customer customer) {
    return customer.tier().equals("VIP") ? VIP_LIMIT : STANDARD_LIMIT;
  }
}

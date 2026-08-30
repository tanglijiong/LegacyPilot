package io.legacypilot.samples.banking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class Task018HiddenTest {
  @Test
  void debitsAvailableBalanceAndRejectsInsufficientFundsBeforeWrites() {
    var transfers = new MemoryTransfers();
    var accounts = new MemoryAccounts(new Account("a1", "c1", new BigDecimal("100.00"), "ACTIVE"));
    var service =
        new TransferService(
            transfers,
            accounts,
            customerId -> Optional.empty(),
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    assertThrows(
        IllegalStateException.class,
        () -> service.transferFromBalance("a1", new BigDecimal("101.00")));
    assertEquals(0, accounts.saveCount);
    assertEquals(0, transfers.values.size());

    service.transferFromBalance("a1", new BigDecimal("40.00"));
    assertEquals(new BigDecimal("60.00"), accounts.account.balance());
    assertEquals(1, accounts.saveCount);
    assertEquals(1, transfers.values.size());
  }

  private static final class MemoryAccounts implements AccountRepository {
    private Account account;
    private int saveCount;

    private MemoryAccounts(Account account) {
      this.account = account;
    }

    public Optional<Account> findById(String accountId) {
      return Optional.ofNullable(account);
    }

    public void save(Account value) {
      saveCount++;
      account = value;
    }
  }

  private static final class MemoryTransfers implements TransferRepository {
    private final List<TransferRecord> values = new ArrayList<>();

    public void save(TransferRecord transfer) {
      values.add(transfer);
    }

    public List<TransferRecord> findByAccountIdAndOccurredAtBetween(
        String accountId, Instant fromInclusive, Instant toExclusive) {
      return List.of();
    }
  }
}

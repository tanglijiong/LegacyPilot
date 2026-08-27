package io.legacypilot.samples.banking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransferServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-27T10:00:00Z");

  @Test
  void persistsTransferAndReturnsHistory() {
    var repository = new MemoryTransferRepository();
    AccountRepository accounts =
        new AccountRepository() {
          @Override
          public java.util.Optional<Account> findById(String accountId) {
            return java.util.Optional.empty();
          }

          @Override
          public void save(Account account) {}
        };
    CustomerRepository customers = customerId -> java.util.Optional.empty();
    var service =
        new TransferService(repository, accounts, customers, Clock.fixed(NOW, ZoneOffset.UTC));

    var created = service.transfer("account-1", new BigDecimal("125.50"));

    assertEquals("account-1", created.accountId());
    assertEquals(NOW, created.occurredAt());
    assertSame(created, repository.transfers.getFirst());
    assertEquals(
        List.of(created), service.history("account-1", NOW.minusSeconds(1), NOW.plusSeconds(1)));
  }

  private static final class MemoryTransferRepository implements TransferRepository {
    private final List<TransferRecord> transfers = new ArrayList<>();

    @Override
    public void save(TransferRecord transfer) {
      transfers.add(transfer);
    }

    @Override
    public List<TransferRecord> findByAccountIdAndOccurredAtBetween(
        String accountId, Instant fromInclusive, Instant toExclusive) {
      return transfers.stream()
          .filter(value -> value.accountId().equals(accountId))
          .filter(value -> !value.occurredAt().isBefore(fromInclusive))
          .filter(value -> value.occurredAt().isBefore(toExclusive))
          .toList();
    }
  }
}

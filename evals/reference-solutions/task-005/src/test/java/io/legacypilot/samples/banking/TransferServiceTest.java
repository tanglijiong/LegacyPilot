package io.legacypilot.samples.banking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TransferServiceTest {

  @Test
  void enforcesStandardVipAndUtcDayRollover() {
    var clock = new MutableClock(Instant.parse("2026-08-27T23:59:00Z"));
    var repository = new MemoryTransferRepository();
    var service = service(repository, clock, "STANDARD");
    service.transfer("account-1", new BigDecimal("50000"));
    assertThrows(
        TransferLimitException.class,
        () -> service.transfer("account-1", new BigDecimal("0.01")));

    clock.instant = Instant.parse("2026-08-28T00:01:00Z");
    service.transfer("account-1", new BigDecimal("50000"));
    assertEquals(2, repository.transfers.size());

    var vipRepository = new MemoryTransferRepository();
    service(vipRepository, clock, "VIP").transfer("account-1", new BigDecimal("200000"));
    assertEquals(1, vipRepository.transfers.size());
  }

  @Test
  void concurrentLimitCheckAndSaveIsAtomic() throws Exception {
    var repository = new MemoryTransferRepository();
    var service =
        service(
            repository,
            Clock.fixed(Instant.parse("2026-08-27T10:00:00Z"), ZoneOffset.UTC),
            "STANDARD");
    var start = new CountDownLatch(1);
    var accepted = new AtomicInteger();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var calls =
          List.of(
              executor.submit(() -> transfer(start, service, accepted)),
              executor.submit(() -> transfer(start, service, accepted)));
      start.countDown();
      for (var call : calls) {
        call.get();
      }
    }
    assertEquals(1, accepted.get());
    assertEquals(1, repository.transfers.size());
  }

  private static void transfer(
      CountDownLatch start, TransferService service, AtomicInteger accepted) {
    try {
      start.await();
      service.transfer("account-1", new BigDecimal("30000"));
      accepted.incrementAndGet();
    } catch (TransferLimitException ignored) {
      // One concurrent request is expected to exceed the limit.
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  private static TransferService service(
      MemoryTransferRepository repository, Clock clock, String tier) {
    AccountRepository accounts =
        new AccountRepository() {
          @Override
          public Optional<Account> findById(String accountId) {
            return Optional.of(new Account(accountId, "customer-1", BigDecimal.TEN, "ACTIVE"));
          }

          @Override
          public void save(Account account) {}
        };
    CustomerRepository customers =
        customerId -> Optional.of(new Customer(customerId, "Customer", tier));
    return new TransferService(repository, accounts, customers, clock);
  }

  private static final class MemoryTransferRepository implements TransferRepository {
    private final List<TransferRecord> transfers = new ArrayList<>();

    @Override
    public synchronized void save(TransferRecord transfer) {
      transfers.add(transfer);
    }

    @Override
    public synchronized List<TransferRecord> findByAccountIdAndOccurredAtBetween(
        String accountId, Instant fromInclusive, Instant toExclusive) {
      return transfers.stream()
          .filter(value -> value.accountId().equals(accountId))
          .filter(value -> !value.occurredAt().isBefore(fromInclusive))
          .filter(value -> value.occurredAt().isBefore(toExclusive))
          .toList();
    }
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}

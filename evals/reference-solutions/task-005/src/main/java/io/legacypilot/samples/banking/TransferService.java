package io.legacypilot.samples.banking;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class TransferService {
  private final TransferRepository repository;
  private final AccountRepository accounts;
  private final CustomerRepository customers;
  private final Clock clock;
  private final DailyTransferPolicy policy = new DailyTransferPolicy();

  public TransferService(
      TransferRepository repository,
      AccountRepository accounts,
      CustomerRepository customers,
      Clock clock) {
    this.repository = Objects.requireNonNull(repository);
    this.accounts = Objects.requireNonNull(accounts);
    this.customers = Objects.requireNonNull(customers);
    this.clock = Objects.requireNonNull(clock);
  }

  public synchronized TransferRecord transfer(String accountId, BigDecimal amount) {
    var account = accounts.findById(accountId).orElseThrow();
    var customer = customers.findById(account.customerId()).orElseThrow();
    var date = LocalDate.now(clock);
    var from = date.atStartOfDay().toInstant(ZoneOffset.UTC);
    var to = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    var used =
        repository.findByAccountIdAndOccurredAtBetween(accountId, from, to).stream()
            .map(TransferRecord::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (used.add(amount).compareTo(policy.limitFor(customer)) > 0) {
      throw new TransferLimitException();
    }
    var transfer =
        new TransferRecord(UUID.randomUUID().toString(), accountId, amount, clock.instant());
    repository.save(transfer);
    return transfer;
  }

  public java.util.List<TransferRecord> history(
      String accountId, Instant fromInclusive, Instant toExclusive) {
    return repository.findByAccountIdAndOccurredAtBetween(
        accountId, fromInclusive, toExclusive);
  }
}

package io.legacypilot.samples.banking;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class TransferService {
  private final TransferRepository repository;
  private final AccountRepository accounts;
  private final CustomerRepository customers;
  private final Clock clock;

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

  public TransferRecord transfer(String accountId, BigDecimal amount) {
    var transfer =
        new TransferRecord(UUID.randomUUID().toString(), accountId, amount, clock.instant());
    repository.save(transfer);
    return transfer;
  }

  public TransferRecord transferFromBalance(String accountId, BigDecimal amount) {
    var account =
        accounts
            .findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("ACCOUNT_NOT_FOUND"));
    var debited = account.debit(amount);
    accounts.save(debited);
    return transfer(accountId, amount);
  }

  public java.util.List<TransferRecord> history(
      String accountId, Instant fromInclusive, Instant toExclusive) {
    return repository.findByAccountIdAndOccurredAtBetween(accountId, fromInclusive, toExclusive);
  }
}

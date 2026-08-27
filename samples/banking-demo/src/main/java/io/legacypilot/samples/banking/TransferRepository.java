package io.legacypilot.samples.banking;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public interface TransferRepository {
  void save(TransferRecord transfer);

  List<TransferRecord> findByAccountIdAndOccurredAtBetween(
      String accountId, Instant fromInclusive, Instant toExclusive);
}

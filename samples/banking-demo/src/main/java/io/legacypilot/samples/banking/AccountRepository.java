package io.legacypilot.samples.banking;

import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountRepository {
  Optional<Account> findById(String accountId);

  void save(Account account);
}

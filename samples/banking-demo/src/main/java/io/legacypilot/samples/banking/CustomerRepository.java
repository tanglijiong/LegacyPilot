package io.legacypilot.samples.banking;

import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public interface CustomerRepository {
  Optional<Customer> findById(String customerId);
}

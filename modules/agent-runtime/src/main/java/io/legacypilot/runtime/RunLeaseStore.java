package io.legacypilot.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface RunLeaseStore {
  Optional<RunLease> acquire(String runId, String owner, Instant now, Duration ttl);

  Optional<RunLease> renew(RunLease lease, Instant now, Duration ttl);

  void release(RunLease lease);

  Optional<RunLease> current(String runId);
}

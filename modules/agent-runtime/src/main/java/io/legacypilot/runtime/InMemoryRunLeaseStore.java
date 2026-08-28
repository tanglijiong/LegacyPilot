package io.legacypilot.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryRunLeaseStore implements RunLeaseStore {
  private final ConcurrentHashMap<String, RunLease> values = new ConcurrentHashMap<>();

  @Override
  public synchronized Optional<RunLease> acquire(
      String runId, String owner, Instant now, Duration ttl) {
    var current = values.get(runId);
    if (current != null && current.activeAt(now) && !current.owner().equals(owner)) {
      return Optional.empty();
    }
    var epoch =
        current == null
            ? 1
            : current.activeAt(now) && current.owner().equals(owner)
                ? current.epoch()
                : current.epoch() + 1;
    var lease = new RunLease(runId, owner, epoch, now.plus(ttl));
    values.put(runId, lease);
    return Optional.of(lease);
  }

  @Override
  public synchronized Optional<RunLease> renew(RunLease lease, Instant now, Duration ttl) {
    var current = values.get(lease.runId());
    if (current == null
        || current.epoch() != lease.epoch()
        || !current.owner().equals(lease.owner())
        || !current.activeAt(now)) {
      return Optional.empty();
    }
    var renewed = new RunLease(lease.runId(), lease.owner(), lease.epoch(), now.plus(ttl));
    values.put(lease.runId(), renewed);
    return Optional.of(renewed);
  }

  @Override
  public synchronized void release(RunLease lease) {
    values.computeIfPresent(
        lease.runId(),
        (ignored, current) ->
            current.epoch() == lease.epoch() && current.owner().equals(lease.owner())
                ? new RunLease(current.runId(), current.owner(), current.epoch(), Instant.EPOCH)
                : current);
  }

  @Override
  public Optional<RunLease> current(String runId) {
    return Optional.ofNullable(values.get(runId));
  }
}

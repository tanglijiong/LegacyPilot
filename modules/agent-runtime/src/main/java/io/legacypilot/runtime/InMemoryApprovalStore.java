package io.legacypilot.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryApprovalStore implements ApprovalStore {
  private final List<RuntimeApproval> values = new CopyOnWriteArrayList<>();

  @Override
  public void save(RuntimeApproval approval) {
    values.add(approval);
  }

  @Override
  public synchronized Optional<RuntimeApproval> consumeMatching(
      String runId, String actionDigest, String planDigest, Instant now) {
    var match =
        values.reversed().stream()
            .filter(approval -> approval.matches(runId, actionDigest, planDigest, now))
            .findFirst();
    match.filter(approval -> approval.scope() == ApprovalScope.ONCE).ifPresent(values::remove);
    return match;
  }
}

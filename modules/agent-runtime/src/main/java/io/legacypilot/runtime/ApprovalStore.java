package io.legacypilot.runtime;

import java.time.Instant;
import java.util.Optional;

public interface ApprovalStore {
  void save(RuntimeApproval approval);

  Optional<RuntimeApproval> consumeMatching(
      String runId, String actionDigest, String planDigest, Instant now);
}

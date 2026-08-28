package io.legacypilot.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RecoveryCoordinator {
  private final FileCheckpointStore checkpoints;
  private final AgentRuntime runtime;

  public RecoveryCoordinator(FileCheckpointStore checkpoints, AgentRuntime runtime) {
    this.checkpoints = Objects.requireNonNull(checkpoints);
    this.runtime = Objects.requireNonNull(runtime);
  }

  public List<RecoveryOutcome> recoverAll() {
    var outcomes = new ArrayList<RecoveryOutcome>();
    for (var runId : checkpoints.runIds()) {
      var checkpoint = checkpoints.load(runId).orElseThrow();
      if (checkpoint.status().terminal()) {
        outcomes.add(
            new RecoveryOutcome(
                runId, RecoveryOutcome.Decision.TERMINAL, checkpoint.status(), "already terminal"));
        continue;
      }
      if (checkpoint.status() == RuntimeStatus.WAITING_FOR_APPROVAL) {
        outcomes.add(
            new RecoveryOutcome(
                runId,
                RecoveryOutcome.Decision.AWAITING_APPROVAL,
                checkpoint.status(),
                checkpoint.observation()));
        continue;
      }
      if (checkpoint.status() == RuntimeStatus.NEEDS_REVIEW) {
        outcomes.add(
            new RecoveryOutcome(
                runId,
                RecoveryOutcome.Decision.NEEDS_REVIEW,
                checkpoint.status(),
                checkpoint.observation()));
        continue;
      }
      try {
        var result = runtime.resume(runId);
        outcomes.add(
            new RecoveryOutcome(
                runId,
                RecoveryOutcome.Decision.RESUMED,
                result.checkpoint().status(),
                result.checkpoint().observation()));
      } catch (RuntimeException exception) {
        outcomes.add(
            new RecoveryOutcome(
                runId,
                RecoveryOutcome.Decision.FAILED,
                checkpoint.status(),
                "recovery failed safely"));
      }
    }
    return List.copyOf(outcomes);
  }
}

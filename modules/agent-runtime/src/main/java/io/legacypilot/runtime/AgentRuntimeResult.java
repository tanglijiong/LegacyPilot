package io.legacypilot.runtime;

import io.legacypilot.observability.RunReport;
import io.legacypilot.verification.VerificationOutcome;
import java.util.Objects;

public record AgentRuntimeResult(
    AgentCheckpoint checkpoint, VerificationOutcome verification, RunReport report) {
  public AgentRuntimeResult {
    Objects.requireNonNull(checkpoint);
  }
}

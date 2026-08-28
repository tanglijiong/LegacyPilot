package io.legacypilot.runtime;

import java.time.Instant;
import java.util.Objects;

public record ActionRecord(
    String actionId,
    String runId,
    String tool,
    String actionDigest,
    String planDigest,
    ActionStatus status,
    int attempts,
    String resultSummary,
    Instant updatedAt) {
  public ActionRecord {
    Objects.requireNonNull(actionId);
    Objects.requireNonNull(runId);
    Objects.requireNonNull(tool);
    Objects.requireNonNull(actionDigest);
    Objects.requireNonNull(planDigest);
    Objects.requireNonNull(status);
    resultSummary = truncate(resultSummary);
    Objects.requireNonNull(updatedAt);
    if (actionId.isBlank() || runId.isBlank() || tool.isBlank() || attempts < 0) {
      throw new IllegalArgumentException("action record is invalid");
    }
  }

  public ActionRecord transition(ActionStatus next, int nextAttempts, String summary, Instant now) {
    return new ActionRecord(
        actionId,
        runId,
        tool,
        actionDigest,
        planDigest,
        next,
        nextAttempts,
        truncate(summary),
        now);
  }

  private static String truncate(String value) {
    var safe = Objects.requireNonNullElse(value, "");
    return safe.length() <= 2_048 ? safe : safe.substring(0, 2_048);
  }
}

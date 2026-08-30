package io.legacypilot.eval;

import java.time.Instant;
import java.util.Objects;

public record EvalAttemptCheckpoint(
    String taskId,
    String attemptId,
    Status status,
    Instant startedAt,
    Instant completedAt,
    EvalTaskResult result) {
  public EvalAttemptCheckpoint {
    Objects.requireNonNull(taskId);
    Objects.requireNonNull(attemptId);
    Objects.requireNonNull(status);
    if (!taskId.matches("task-[0-9]{3}") || attemptId.isBlank()) {
      throw new IllegalArgumentException("eval attempt checkpoint is invalid");
    }
    if (status == Status.PENDING && (startedAt != null || completedAt != null || result != null)) {
      throw new IllegalArgumentException("pending eval attempt contains execution state");
    }
    if (status == Status.RUNNING && (startedAt == null || completedAt != null || result != null)) {
      throw new IllegalArgumentException("running eval attempt state is invalid");
    }
    if (status == Status.COMPLETED
        && (startedAt == null || completedAt == null || result == null)) {
      throw new IllegalArgumentException("completed eval attempt state is invalid");
    }
    if (status == Status.NEEDS_REVIEW && startedAt == null) {
      throw new IllegalArgumentException("review eval attempt has not started");
    }
  }

  public static EvalAttemptCheckpoint pending(String runId, String taskId) {
    return new EvalAttemptCheckpoint(
        taskId, runId + "-" + taskId + "-a1", Status.PENDING, null, null, null);
  }

  public EvalAttemptCheckpoint running(Instant now) {
    return new EvalAttemptCheckpoint(taskId, attemptId, Status.RUNNING, now, null, null);
  }

  public EvalAttemptCheckpoint completed(EvalTaskResult taskResult, Instant now) {
    return new EvalAttemptCheckpoint(
        taskId, attemptId, Status.COMPLETED, startedAt, now, taskResult);
  }

  public EvalAttemptCheckpoint needsReview() {
    return new EvalAttemptCheckpoint(taskId, attemptId, Status.NEEDS_REVIEW, startedAt, null, null);
  }

  public enum Status {
    PENDING,
    RUNNING,
    COMPLETED,
    NEEDS_REVIEW
  }
}

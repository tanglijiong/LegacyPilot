package io.legacypilot.application.execution;

import java.time.Instant;
import java.util.Objects;

public record RunExecutionCommand(
    String id,
    String runId,
    Operation operation,
    long acceptedRunVersion,
    String idempotencyKey,
    String actor,
    String correlationId,
    Status status,
    int attempts,
    String owner,
    Instant leaseExpiresAt,
    String error,
    Instant requestedAt,
    Instant updatedAt) {

  public enum Operation {
    EXECUTE,
    RESUME,
    RETRY,
    REPLAN
  }

  public enum Status {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
  }

  public RunExecutionCommand {
    Objects.requireNonNull(id);
    Objects.requireNonNull(runId);
    Objects.requireNonNull(operation);
    Objects.requireNonNull(idempotencyKey);
    Objects.requireNonNull(actor);
    Objects.requireNonNull(correlationId);
    Objects.requireNonNull(status);
    Objects.requireNonNull(requestedAt);
    Objects.requireNonNull(updatedAt);
    error = Objects.requireNonNullElse(error, "");
    if (id.isBlank()
        || runId.isBlank()
        || idempotencyKey.isBlank()
        || actor.isBlank()
        || correlationId.isBlank()
        || acceptedRunVersion < 0
        || attempts < 0) {
      throw new IllegalArgumentException("execution command is invalid");
    }
  }
}

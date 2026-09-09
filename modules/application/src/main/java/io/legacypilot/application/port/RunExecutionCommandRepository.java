package io.legacypilot.application.port;

import io.legacypilot.application.execution.RunExecutionCommand;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RunExecutionCommandRepository {
  RunExecutionCommand add(RunExecutionCommand command);

  Optional<RunExecutionCommand> findById(String commandId);

  Optional<RunExecutionCommand> findByRunAndIdempotencyKey(String runId, String idempotencyKey);

  Optional<RunExecutionCommand> findByRunOperationAndVersion(
      String runId, RunExecutionCommand.Operation operation, long runVersion);

  List<RunExecutionCommand> findByRun(String runId);

  List<RunExecutionCommand> findClaimable(Instant now, int limit);

  boolean claim(String commandId, String owner, Instant now, Instant leaseExpiresAt);

  boolean renew(String commandId, String owner, Instant now, Instant leaseExpiresAt);

  void complete(String commandId, String owner, Instant now);

  void fail(String commandId, String owner, String error, Instant now);

  int cancelQueued(String runId, Instant now);
}

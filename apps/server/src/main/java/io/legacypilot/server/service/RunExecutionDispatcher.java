package io.legacypilot.server.service;

import io.legacypilot.application.error.ConcurrentUpdateException;
import io.legacypilot.application.execution.RunExecutionCommand;
import io.legacypilot.application.port.RunExecutionCommandRepository;
import io.legacypilot.application.port.TaskRunRepository;
import io.legacypilot.application.service.GetRunStatusUseCase;
import io.legacypilot.domain.run.RunStatus;
import io.legacypilot.domain.run.TaskRun;
import io.legacypilot.observability.TraceSink;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public final class RunExecutionDispatcher {
  private static final Logger LOGGER = LoggerFactory.getLogger(RunExecutionDispatcher.class);
  private static final int MAXIMUM_IDEMPOTENCY_KEY_LENGTH = 128;
  private static final int MAXIMUM_ERROR_LENGTH = 2_048;
  private static final int RECOVERY_BATCH_SIZE = 100;
  private static final Set<RunStatus> ACTIVE_STATUSES =
      Set.of(RunStatus.PLANNING, RunStatus.EXECUTING, RunStatus.VERIFYING, RunStatus.RECOVERING);

  private final RunExecutionCommandRepository commands;
  private final TaskRunRepository runs;
  private final GetRunStatusUseCase getRun;
  private final RunExecutionService executions;
  private final TraceSink trace;
  private final Executor executor;
  private final ScheduledExecutorService scheduler;
  private final Clock clock;
  private final Duration leaseTtl;
  private final Duration scanInterval;
  private final String owner = "dispatcher-" + UUID.randomUUID();
  private final Set<String> scheduled = ConcurrentHashMap.newKeySet();

  public RunExecutionDispatcher(
      RunExecutionCommandRepository commands,
      TaskRunRepository runs,
      GetRunStatusUseCase getRun,
      RunExecutionService executions,
      TraceSink trace,
      @Qualifier("runCommandExecutor") Executor executor,
      @Qualifier("runCommandScheduler") ScheduledExecutorService scheduler,
      Clock clock,
      @Value("${legacy-pilot.execution.command-lease-ttl:2m}") Duration leaseTtl,
      @Value("${legacy-pilot.execution.queue-scan-interval:5s}") Duration scanInterval) {
    this.commands = commands;
    this.runs = runs;
    this.getRun = getRun;
    this.executions = executions;
    this.trace = trace;
    this.executor = executor;
    this.scheduler = scheduler;
    this.clock = clock;
    if (leaseTtl.isZero()
        || leaseTtl.isNegative()
        || scanInterval.isZero()
        || scanInterval.isNegative()) {
      throw new IllegalArgumentException("command lease and scan interval must be positive");
    }
    this.leaseTtl = leaseTtl;
    this.scanInterval = scanInterval;
  }

  public RunExecutionCommand accept(
      String runId,
      RunExecutionCommand.Operation operation,
      String idempotencyKey,
      String actor,
      String correlationId) {
    return accept(runId, operation, idempotencyKey, actor, correlationId, null);
  }

  public RunExecutionCommand accept(
      String runId,
      RunExecutionCommand.Operation operation,
      String idempotencyKey,
      String actor,
      String correlationId,
      Long expectedVersion) {
    validate(idempotencyKey, actor);
    var existing = commands.findByRunAndIdempotencyKey(runId, idempotencyKey).orElse(null);
    if (existing != null) {
      if (existing.operation() != operation) {
        throw new IllegalStateException("idempotency key is already used for another operation");
      }
      submit(existing.id());
      return existing;
    }

    var run = getRun.get(runId);
    if (expectedVersion != null && run.version() != expectedVersion) {
      throw new ConcurrentUpdateException("TaskRun", runId);
    }
    requireAcceptable(run.status(), operation);
    var now = clock.instant();
    var command =
        new RunExecutionCommand(
            "command-" + UUID.randomUUID(),
            runId,
            operation,
            run.version(),
            idempotencyKey,
            actor,
            normalizedCorrelationId(correlationId),
            RunExecutionCommand.Status.QUEUED,
            0,
            null,
            null,
            "",
            now,
            now);
    var created = false;
    try {
      commands.add(command);
      created = true;
    } catch (DataIntegrityViolationException exception) {
      command =
          commands
              .findByRunOperationAndVersion(runId, operation, run.version())
              .orElseThrow(() -> exception);
      if (!command.idempotencyKey().equals(idempotencyKey)) {
        throw new IllegalStateException(
            "another execution command was accepted for this run state", exception);
      }
    }
    if (created) {
      trace.record(
          runId,
          "command.accepted",
          now,
          java.util.Map.of(
              "operation", operation.name(), "correlationId", command.correlationId()));
    }
    if (operation == RunExecutionCommand.Operation.EXECUTE
        && run.status() == RunStatus.WORKSPACE_READY) {
      markQueued(run, now);
    } else if (operation == RunExecutionCommand.Operation.RETRY
        || operation == RunExecutionCommand.Operation.REPLAN) {
      markRetrying(run, operation, now);
    }
    submit(command.id());
    return command;
  }

  public RunExecutionCommand get(String commandId) {
    return commands
        .findById(commandId)
        .orElseThrow(
            () ->
                new io.legacypilot.application.error.ResourceNotFoundException(
                    "RunExecutionCommand", commandId));
  }

  @EventListener(ApplicationReadyEvent.class)
  public void startRecoveryScanner() {
    scheduler.scheduleWithFixedDelay(
        this::scheduleClaimable, 0, scanInterval.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void scheduleClaimable() {
    try {
      for (var command : commands.findClaimable(clock.instant(), RECOVERY_BATCH_SIZE)) {
        submit(command.id());
      }
    } catch (RuntimeException exception) {
      LOGGER.warn("Unable to scan durable execution commands", exception);
    }
  }

  private void submit(String commandId) {
    if (scheduled.add(commandId)) {
      executor.execute(() -> runCommand(commandId));
    }
  }

  void runCommand(String commandId) {
    ScheduledFuture<?> heartbeat = null;
    try {
      var now = clock.instant();
      if (!commands.claim(commandId, owner, now, now.plus(leaseTtl))) {
        return;
      }
      var command = commands.findById(commandId).orElseThrow();
      heartbeat =
          scheduler.scheduleWithFixedDelay(
              () -> renew(commandId),
              Math.max(1, leaseTtl.toMillis() / 3),
              Math.max(1, leaseTtl.toMillis() / 3),
              TimeUnit.MILLISECONDS);
      try (var ignored = MDC.putCloseable("correlationId", command.correlationId())) {
        dispatch(command);
      }
      commands.complete(commandId, owner, clock.instant());
    } catch (RuntimeException exception) {
      commands.fail(commandId, owner, boundedError(exception), clock.instant());
      LOGGER.error("Background execution command failed: {}", commandId, exception);
    } finally {
      if (heartbeat != null) {
        heartbeat.cancel(false);
      }
      scheduled.remove(commandId);
    }
  }

  private void dispatch(RunExecutionCommand command) {
    var run = getRun.get(command.runId());
    if (run.status().isTerminal()) {
      return;
    }
    if (command.operation() == RunExecutionCommand.Operation.EXECUTE) {
      if (run.status() == RunStatus.WAITING_FOR_APPROVAL
          || run.status() == RunStatus.NEEDS_REVIEW) {
        return;
      }
      if (run.status() == RunStatus.WORKSPACE_READY) {
        run =
            runs.update(
                run.transitionTo(
                    RunStatus.QUEUED, clock.instant(), "recovered queued execution command"));
      }
      if (run.status() == RunStatus.QUEUED) {
        executions.execute(run.id().value(), command.actor());
      } else if (ACTIVE_STATUSES.contains(run.status())) {
        executions.recover(run.id().value());
      }
      return;
    }
    if (command.operation() == RunExecutionCommand.Operation.RETRY
        || command.operation() == RunExecutionCommand.Operation.REPLAN) {
      if (run.status() == RunStatus.RECOVERING) {
        executions.retry(
            run.id().value(), command.operation() == RunExecutionCommand.Operation.REPLAN);
      } else if (ACTIVE_STATUSES.contains(run.status())) {
        // The process may have stopped after retry() reopened the durable runtime checkpoint.
        // At that point the retry command still owns the work, but it must resume the reopened
        // checkpoint instead of trying to reset the terminal checkpoint a second time.
        executions.recover(run.id().value());
      }
      return;
    }
    if (run.status() == RunStatus.WAITING_FOR_APPROVAL || run.status() == RunStatus.NEEDS_REVIEW) {
      executions.resume(run.id().value());
    } else if (ACTIVE_STATUSES.contains(run.status())) {
      executions.recover(run.id().value());
    }
  }

  private void renew(String commandId) {
    var now = clock.instant();
    if (!commands.renew(commandId, owner, now, now.plus(leaseTtl))) {
      LOGGER.warn("Execution command lease was lost: {}", commandId);
    }
  }

  private void markQueued(TaskRun acceptedRun, Instant now) {
    var current = acceptedRun;
    for (var attempt = 0; attempt < 3; attempt++) {
      if (current.status() != RunStatus.WORKSPACE_READY) {
        // A recovery scanner may claim the durable command between its insert and this update.
        // Any later lifecycle state proves that the accepted command is already progressing.
        return;
      }
      try {
        runs.update(current.transitionTo(RunStatus.QUEUED, now, "execution command accepted"));
        return;
      } catch (ConcurrentUpdateException exception) {
        current = getRun.get(acceptedRun.id().value());
      }
    }
    throw new ConcurrentUpdateException("TaskRun", acceptedRun.id().value());
  }

  private void markRetrying(
      TaskRun acceptedRun, RunExecutionCommand.Operation operation, Instant now) {
    try {
      runs.update(
          acceptedRun.beginRetry(
              operation == RunExecutionCommand.Operation.REPLAN
                  ? RunStatus.PLANNING
                  : RunStatus.EXECUTING,
              now,
              operation == RunExecutionCommand.Operation.REPLAN
                  ? "explicit replan accepted"
                  : "explicit retry accepted"));
    } catch (ConcurrentUpdateException exception) {
      throw new ConcurrentUpdateException("TaskRun", acceptedRun.id().value());
    }
  }

  private static void requireAcceptable(RunStatus status, RunExecutionCommand.Operation operation) {
    var accepted =
        switch (operation) {
          case EXECUTE -> status == RunStatus.WORKSPACE_READY;
          case RESUME ->
              status == RunStatus.WAITING_FOR_APPROVAL || status == RunStatus.NEEDS_REVIEW;
          case RETRY -> status == RunStatus.FAILED;
          case REPLAN -> status == RunStatus.FAILED || status == RunStatus.CANCELLED;
        };
    if (!accepted) {
      throw new IllegalStateException(operation + " cannot be accepted while run is " + status);
    }
  }

  private static void validate(String idempotencyKey, String actor) {
    if (idempotencyKey == null
        || idempotencyKey.isBlank()
        || idempotencyKey.length() > MAXIMUM_IDEMPOTENCY_KEY_LENGTH
        || !idempotencyKey.matches("[A-Za-z0-9._:-]+")) {
      throw new IllegalArgumentException("Idempotency-Key is missing or invalid");
    }
    if (actor == null || actor.isBlank()) {
      throw new IllegalArgumentException("authenticated actor is required");
    }
  }

  private static String normalizedCorrelationId(String correlationId) {
    if (correlationId == null || correlationId.isBlank()) {
      return UUID.randomUUID().toString();
    }
    return correlationId;
  }

  private static String boundedError(RuntimeException exception) {
    var message =
        exception.getMessage() == null
            ? exception.getClass().getSimpleName()
            : exception.getMessage();
    return message.substring(0, Math.min(message.length(), MAXIMUM_ERROR_LENGTH));
  }
}

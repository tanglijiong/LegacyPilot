package io.legacypilot.server.service;

import io.legacypilot.application.error.ConcurrentUpdateException;
import io.legacypilot.application.execution.RunExecutionCommand;
import io.legacypilot.application.port.RunExecutionCommandRepository;
import io.legacypilot.application.port.TaskRunRepository;
import io.legacypilot.application.service.GetRunStatusUseCase;
import io.legacypilot.domain.run.RunStatus;
import io.legacypilot.domain.run.TaskRun;
import io.legacypilot.observability.TraceEvent;
import io.legacypilot.observability.TraceSink;
import io.legacypilot.runtime.ActionJournal;
import io.legacypilot.runtime.ActionStatus;
import io.legacypilot.runtime.AgentCheckpoint;
import io.legacypilot.runtime.CheckpointStore;
import io.legacypilot.tool.spi.Idempotency;
import io.legacypilot.tool.spi.ToolExecutor;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Classifies failures and exposes only recovery operations that are safe for the current version.
 */
@Service
public final class RunRecoveryService {
  private static final int MAXIMUM_REASON_LENGTH = 1_000;

  private final GetRunStatusUseCase getRun;
  private final TaskRunRepository runs;
  private final RunExecutionCommandRepository commands;
  private final RunExecutionService executions;
  private final CheckpointStore checkpoints;
  private final ActionJournal journal;
  private final ToolExecutor tools;
  private final TraceSink trace;
  private final Clock clock;

  public RunRecoveryService(
      GetRunStatusUseCase getRun,
      TaskRunRepository runs,
      RunExecutionCommandRepository commands,
      RunExecutionService executions,
      CheckpointStore checkpoints,
      ActionJournal journal,
      ToolExecutor tools,
      TraceSink trace,
      Clock clock) {
    this.getRun = Objects.requireNonNull(getRun);
    this.runs = Objects.requireNonNull(runs);
    this.commands = Objects.requireNonNull(commands);
    this.executions = Objects.requireNonNull(executions);
    this.checkpoints = Objects.requireNonNull(checkpoints);
    this.journal = Objects.requireNonNull(journal);
    this.tools = Objects.requireNonNull(tools);
    this.trace = Objects.requireNonNull(trace);
    this.clock = Objects.requireNonNull(clock);
  }

  public RecoveryView inspect(String runId) {
    var run = getRun.get(runId);
    var checkpoint = checkpoints.load(runId).orElse(null);
    var events = trace.events(runId);
    var category = category(run, checkpoint, events);
    var replay = replaySafety(runId);
    var actions = allowedActions(run.status(), category, replay);
    return new RecoveryView(
        run.id().value(),
        run.status().name(),
        run.version(),
        category.name(),
        replay.name(),
        diagnosis(run, checkpoint, category),
        actions,
        attempts(runId),
        checkpoint == null ? null : checkpoint.updatedAt());
  }

  public TaskRun cancel(String runId, long expectedVersion, String actor, String reason) {
    var run = requireVersion(runId, expectedVersion);
    if (run.status().isTerminal()) {
      throw new IllegalStateException("terminal run cannot be cancelled");
    }
    if (hasActiveUncertainAction(runId)) {
      throw new IllegalStateException(
          "run has an active action; wait for a safe checkpoint or request human review");
    }
    var safeReason = boundedReason(reason, "cancelled by user");
    var cancelled = runs.update(run.transitionTo(RunStatus.CANCELLED, clock.instant(), safeReason));
    executions.requestCancellation(runId);
    commands.cancelQueued(runId, clock.instant());
    trace.record(
        runId,
        "run.cancelled",
        clock.instant(),
        Map.of("actor", actor, "reason", safeReason, "version", Long.toString(expectedVersion)));
    return cancelled;
  }

  public TaskRun requireVersion(String runId, long expectedVersion) {
    var run = getRun.get(runId);
    if (run.version() != expectedVersion) {
      throw new ConcurrentUpdateException("TaskRun", runId);
    }
    return run;
  }

  public TaskRun recordDecision(String runId, long expectedVersion, String actor, String decision) {
    var run = requireVersion(runId, expectedVersion);
    return runs.update(
        run.recordDecision(clock.instant(), decision.toLowerCase(Locale.ROOT) + " by " + actor));
  }

  public void requireAction(String runId, long expectedVersion, String action) {
    requireVersion(runId, expectedVersion);
    var recovery = inspect(runId);
    if (!recovery.allowedActions().contains(action)) {
      throw new IllegalStateException(
          action + " is unsafe while recovery state is " + recovery.replaySafety());
    }
  }

  private FailureCategory category(
      TaskRun run, AgentCheckpoint checkpoint, List<TraceEvent> events) {
    if (run.status() == RunStatus.SUCCEEDED) {
      return FailureCategory.NONE;
    }
    if (run.status() == RunStatus.CANCELLED) {
      return FailureCategory.USER_CANCELLED;
    }
    if (run.status() == RunStatus.WAITING_FOR_APPROVAL || run.status() == RunStatus.NEEDS_REVIEW) {
      return FailureCategory.POLICY_BLOCK;
    }
    var latestCommand =
        commands.findByRun(run.id().value()).stream().reduce((left, right) -> right);
    if (latestCommand.isPresent()
        && latestCommand.orElseThrow().status() == RunExecutionCommand.Status.FAILED) {
      return FailureCategory.INFRASTRUCTURE_FAILURE;
    }
    for (var event : events.reversed()) {
      if (event.type().equals("verification.completed")
          || event.type().equals("artifact.verification.completed")) {
        if (!Boolean.parseBoolean(event.attributes().getOrDefault("success", "false"))) {
          return FailureCategory.VERIFICATION_FAILURE;
        }
      }
      if (event.type().equals("model.failed")) {
        return FailureCategory.MODEL_FAILURE;
      }
      if (event.type().equals("task-mode.denied") || event.type().equals("approval.denied")) {
        return FailureCategory.POLICY_BLOCK;
      }
      if (event.type().equals("tool.completed")) {
        var status = event.attributes().getOrDefault("status", "UNKNOWN");
        if (!status.equals("SUCCESS") && !status.equals("APPROVAL_REQUIRED")) {
          return FailureCategory.TOOL_FAILURE;
        }
      }
    }
    if (checkpoint != null && checkpoint.status().name().equals("BUDGET_EXHAUSTED")) {
      return FailureCategory.POLICY_BLOCK;
    }
    return run.status() == RunStatus.FAILED
        ? FailureCategory.INFRASTRUCTURE_FAILURE
        : FailureCategory.NONE;
  }

  private ReplaySafety replaySafety(String runId) {
    for (var record : journal.records(runId)) {
      if (record.status() == ActionStatus.RUNNING || record.status() == ActionStatus.NEEDS_REVIEW) {
        return ReplaySafety.HUMAN_REVIEW_REQUIRED;
      }
      if (record.status() == ActionStatus.FAILED
          && tools
              .descriptor(record.tool())
              .map(descriptor -> descriptor.idempotency() != Idempotency.IDEMPOTENT)
              .orElse(true)) {
        return ReplaySafety.HUMAN_REVIEW_REQUIRED;
      }
    }
    return ReplaySafety.SAFE_FROM_CHECKPOINT;
  }

  private boolean hasActiveUncertainAction(String runId) {
    return journal.records(runId).stream()
        .anyMatch(record -> record.status() == ActionStatus.RUNNING);
  }

  private static List<String> allowedActions(
      RunStatus status, FailureCategory category, ReplaySafety replay) {
    var actions = new ArrayList<String>();
    actions.add("VIEW_DIAGNOSTICS");
    if (!status.isTerminal()) {
      if (status == RunStatus.WAITING_FOR_APPROVAL) {
        actions.add("WAIT_FOR_APPROVAL");
      } else if (status == RunStatus.NEEDS_REVIEW || replay == ReplaySafety.HUMAN_REVIEW_REQUIRED) {
        actions.add("HUMAN_REVIEW");
      }
      if (status != RunStatus.NEEDS_REVIEW && replay != ReplaySafety.HUMAN_REVIEW_REQUIRED) {
        actions.add("CANCEL");
      }
      return List.copyOf(actions);
    }
    if (status == RunStatus.SUCCEEDED) {
      return List.copyOf(actions);
    }
    if (replay == ReplaySafety.HUMAN_REVIEW_REQUIRED) {
      actions.add("HUMAN_REVIEW");
      actions.add("REPLAN");
      return List.copyOf(actions);
    }
    if (category != FailureCategory.POLICY_BLOCK && category != FailureCategory.USER_CANCELLED) {
      actions.add("RETRY_CURRENT_STEP");
    }
    actions.add("REPLAN");
    return List.copyOf(actions);
  }

  private List<AttemptView> attempts(String runId) {
    var values = commands.findByRun(runId);
    var result = new ArrayList<AttemptView>();
    for (var index = 0; index < values.size(); index++) {
      var command = values.get(index);
      result.add(
          new AttemptView(
              index + 1,
              command.id(),
              command.operation().name(),
              command.status().name(),
              command.attempts(),
              command.acceptedRunVersion(),
              command.actor(),
              command.correlationId(),
              command.error(),
              command.requestedAt(),
              command.updatedAt()));
    }
    return List.copyOf(result);
  }

  private static String diagnosis(
      TaskRun run, AgentCheckpoint checkpoint, FailureCategory category) {
    if (checkpoint != null && !checkpoint.observation().isBlank()) {
      return checkpoint.observation();
    }
    if (run.terminalReason() != null && !run.terminalReason().isBlank()) {
      return run.terminalReason();
    }
    return switch (category) {
      case NONE -> "run is healthy";
      case USER_CANCELLED -> "cancelled by user at a safe checkpoint";
      case MODEL_FAILURE -> "model request failed";
      case TOOL_FAILURE -> "tool execution failed";
      case POLICY_BLOCK -> "policy or approval blocked execution";
      case VERIFICATION_FAILURE -> "required verification failed";
      case INFRASTRUCTURE_FAILURE -> "execution infrastructure failed";
    };
  }

  private static String boundedReason(String reason, String fallback) {
    var value = reason == null || reason.isBlank() ? fallback : reason.trim();
    if (value.length() > MAXIMUM_REASON_LENGTH) {
      throw new IllegalArgumentException("reason must not exceed 1000 characters");
    }
    return value;
  }

  enum FailureCategory {
    NONE,
    USER_CANCELLED,
    MODEL_FAILURE,
    TOOL_FAILURE,
    POLICY_BLOCK,
    VERIFICATION_FAILURE,
    INFRASTRUCTURE_FAILURE
  }

  enum ReplaySafety {
    SAFE_FROM_CHECKPOINT,
    HUMAN_REVIEW_REQUIRED
  }

  public record RecoveryView(
      String runId,
      String status,
      long expectedVersion,
      String failureCategory,
      String replaySafety,
      String diagnosis,
      List<String> allowedActions,
      List<AttemptView> attempts,
      Instant checkpointUpdatedAt) {
    public RecoveryView {
      allowedActions = List.copyOf(allowedActions);
      attempts = List.copyOf(attempts);
    }

    @Override
    public List<String> allowedActions() {
      return List.copyOf(allowedActions);
    }

    @Override
    public List<AttemptView> attempts() {
      return List.copyOf(attempts);
    }
  }

  public record AttemptView(
      int number,
      String commandId,
      String operation,
      String status,
      int workerClaims,
      long acceptedRunVersion,
      String actor,
      String correlationId,
      String error,
      Instant requestedAt,
      Instant updatedAt) {}
}

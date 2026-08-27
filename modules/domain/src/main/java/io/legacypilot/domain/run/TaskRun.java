package io.legacypilot.domain.run;

import io.legacypilot.domain.task.TaskId;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Aggregate root for a durable task execution. */
public final class TaskRun {

  private static final Map<RunStatus, Set<RunStatus>> ALLOWED_TRANSITIONS =
      Map.of(
          RunStatus.CREATED,
          EnumSet.of(RunStatus.PREPARING_WORKSPACE, RunStatus.CANCELLED),
          RunStatus.PREPARING_WORKSPACE,
          EnumSet.of(
              RunStatus.WORKSPACE_READY,
              RunStatus.RECOVERING,
              RunStatus.FAILED,
              RunStatus.CANCELLED),
          RunStatus.WORKSPACE_READY,
          EnumSet.of(RunStatus.PLANNING, RunStatus.RECOVERING, RunStatus.CANCELLED),
          RunStatus.PLANNING,
          EnumSet.of(
              RunStatus.WAITING_FOR_APPROVAL,
              RunStatus.EXECUTING,
              RunStatus.RECOVERING,
              RunStatus.FAILED,
              RunStatus.CANCELLED),
          RunStatus.WAITING_FOR_APPROVAL,
          EnumSet.of(
              RunStatus.EXECUTING, RunStatus.RECOVERING, RunStatus.FAILED, RunStatus.CANCELLED),
          RunStatus.EXECUTING,
          EnumSet.of(
              RunStatus.VERIFYING, RunStatus.RECOVERING, RunStatus.FAILED, RunStatus.CANCELLED),
          RunStatus.VERIFYING,
          EnumSet.of(
              RunStatus.EXECUTING,
              RunStatus.SUCCEEDED,
              RunStatus.RECOVERING,
              RunStatus.FAILED,
              RunStatus.CANCELLED),
          RunStatus.RECOVERING,
          EnumSet.of(RunStatus.FAILED, RunStatus.CANCELLED));

  private final RunId id;
  private final TaskId taskId;
  private final RunStatus status;
  private final long version;
  private final WorkspaceId workspaceId;
  private final RunStatus recoveryTarget;
  private final String terminalReason;
  private final Instant createdAt;
  private final Instant updatedAt;
  private final List<StatusTransition> history;

  private TaskRun(
      RunId id,
      TaskId taskId,
      RunStatus status,
      long version,
      WorkspaceId workspaceId,
      RunStatus recoveryTarget,
      String terminalReason,
      Instant createdAt,
      Instant updatedAt,
      List<StatusTransition> history) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.taskId = Objects.requireNonNull(taskId, "taskId must not be null");
    this.status = Objects.requireNonNull(status, "status must not be null");
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
    this.version = version;
    this.workspaceId = workspaceId;
    this.recoveryTarget = recoveryTarget;
    this.terminalReason = terminalReason;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    this.history = List.copyOf(history);
  }

  public static TaskRun create(RunId id, TaskId taskId, Instant now) {
    return new TaskRun(id, taskId, RunStatus.CREATED, 0, null, null, null, now, now, List.of());
  }

  public static TaskRun restore(
      RunId id,
      TaskId taskId,
      RunStatus status,
      long version,
      WorkspaceId workspaceId,
      RunStatus recoveryTarget,
      String terminalReason,
      Instant createdAt,
      Instant updatedAt,
      List<StatusTransition> history) {
    return new TaskRun(
        id,
        taskId,
        status,
        version,
        workspaceId,
        recoveryTarget,
        terminalReason,
        createdAt,
        updatedAt,
        history);
  }

  public TaskRun transitionTo(RunStatus target, Instant now, String reason) {
    Objects.requireNonNull(target, "target must not be null");
    Objects.requireNonNull(now, "now must not be null");
    if (target == RunStatus.RECOVERING) {
      return beginRecovery(now, reason);
    }
    if (!ALLOWED_TRANSITIONS.getOrDefault(status, Set.of()).contains(target)) {
      throw new InvalidStateTransitionException(status, target);
    }
    return copy(
        target,
        workspaceId,
        null,
        terminalReasonFor(target, reason),
        now,
        append(target, now, reason));
  }

  public TaskRun workspaceReady(WorkspaceId workspace, Instant now) {
    Objects.requireNonNull(workspace, "workspace must not be null");
    if (status != RunStatus.PREPARING_WORKSPACE) {
      throw new InvalidStateTransitionException(status, RunStatus.WORKSPACE_READY);
    }
    return copy(
        RunStatus.WORKSPACE_READY,
        workspace,
        null,
        null,
        now,
        append(RunStatus.WORKSPACE_READY, now, "workspace prepared"));
  }

  public TaskRun beginRecovery(Instant now, String reason) {
    if (status == RunStatus.CREATED || status == RunStatus.RECOVERING || status.isTerminal()) {
      throw new InvalidStateTransitionException(status, RunStatus.RECOVERING);
    }
    return copy(
        RunStatus.RECOVERING,
        workspaceId,
        status,
        null,
        now,
        append(RunStatus.RECOVERING, now, reason));
  }

  public TaskRun recover(Instant now) {
    Objects.requireNonNull(now, "now must not be null");
    if (status != RunStatus.RECOVERING || recoveryTarget == null) {
      throw new IllegalStateException("Task run is not recoverable");
    }
    return copy(
        recoveryTarget, workspaceId, null, null, now, append(recoveryTarget, now, "recovered"));
  }

  public TaskRun withVersion(long newVersion) {
    return new TaskRun(
        id,
        taskId,
        status,
        newVersion,
        workspaceId,
        recoveryTarget,
        terminalReason,
        createdAt,
        updatedAt,
        history);
  }

  private TaskRun copy(
      RunStatus newStatus,
      WorkspaceId newWorkspace,
      RunStatus newRecoveryTarget,
      String newTerminalReason,
      Instant now,
      List<StatusTransition> newHistory) {
    return new TaskRun(
        id,
        taskId,
        newStatus,
        version,
        newWorkspace,
        newRecoveryTarget,
        newTerminalReason,
        createdAt,
        now,
        newHistory);
  }

  private List<StatusTransition> append(RunStatus target, Instant now, String reason) {
    var transitions = new java.util.ArrayList<>(history);
    transitions.add(new StatusTransition(history.size() + 1, status, target, now, reason));
    return transitions;
  }

  private static String terminalReasonFor(RunStatus target, String reason) {
    return target.isTerminal() ? Objects.requireNonNullElse(reason, "") : null;
  }

  public RunId id() {
    return id;
  }

  public TaskId taskId() {
    return taskId;
  }

  public RunStatus status() {
    return status;
  }

  public long version() {
    return version;
  }

  public WorkspaceId workspaceId() {
    return workspaceId;
  }

  public RunStatus recoveryTarget() {
    return recoveryTarget;
  }

  public String terminalReason() {
    return terminalReason;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public List<StatusTransition> history() {
    return history;
  }
}

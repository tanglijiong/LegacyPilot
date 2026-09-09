package io.legacypilot.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.legacypilot.application.error.ConcurrentUpdateException;
import io.legacypilot.application.execution.RunExecutionCommand;
import io.legacypilot.application.port.RunExecutionCommandRepository;
import io.legacypilot.application.port.TaskRunRepository;
import io.legacypilot.application.service.GetRunStatusUseCase;
import io.legacypilot.domain.run.RunId;
import io.legacypilot.domain.run.RunStatus;
import io.legacypilot.domain.run.TaskRun;
import io.legacypilot.domain.run.WorkspaceId;
import io.legacypilot.domain.task.TaskId;
import io.legacypilot.observability.InMemoryTraceSink;
import io.legacypilot.observability.SensitiveDataRedactor;
import io.legacypilot.runtime.ActionJournal;
import io.legacypilot.runtime.ActionRecord;
import io.legacypilot.runtime.ActionStatus;
import io.legacypilot.runtime.CheckpointStore;
import io.legacypilot.tool.spi.Idempotency;
import io.legacypilot.tool.spi.ToolDescriptor;
import io.legacypilot.tool.spi.ToolExecutor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RunRecoveryServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");
  private final GetRunStatusUseCase getRun = mock(GetRunStatusUseCase.class);
  private final TaskRunRepository runs = mock(TaskRunRepository.class);
  private final RunExecutionCommandRepository commands = mock(RunExecutionCommandRepository.class);
  private final RunExecutionService executions = mock(RunExecutionService.class);
  private final CheckpointStore checkpoints = mock(CheckpointStore.class);
  private final ActionJournal journal = mock(ActionJournal.class);
  private final ToolExecutor tools = mock(ToolExecutor.class);
  private final InMemoryTraceSink trace = new InMemoryTraceSink(new SensitiveDataRedactor(8_192));
  private RunRecoveryService service;

  @BeforeEach
  void setUp() {
    when(commands.findByRun("run-1")).thenReturn(List.of(command()));
    when(journal.records("run-1")).thenReturn(List.of());
    when(checkpoints.load("run-1")).thenReturn(Optional.empty());
    when(runs.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
    service =
        new RunRecoveryService(
            getRun,
            runs,
            commands,
            executions,
            checkpoints,
            journal,
            tools,
            trace,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void classifiesModelFailureAndRetainsAttemptEvidence() {
    var failed = failedRun();
    when(getRun.get("run-1")).thenReturn(failed);
    trace.record("run-1", "model.failed", NOW, Map.of("type", "TIMEOUT", "retryable", "true"));

    var recovery = service.inspect("run-1");

    assertEquals("MODEL_FAILURE", recovery.failureCategory());
    assertEquals("SAFE_FROM_CHECKPOINT", recovery.replaySafety());
    assertTrue(recovery.allowedActions().contains("RETRY_CURRENT_STEP"));
    assertEquals("correlation-1", recovery.attempts().getFirst().correlationId());
  }

  @Test
  void hidesAutomaticRetryForAnUncertainNonIdempotentAction() {
    var failed = failedRun();
    var descriptor = mock(ToolDescriptor.class);
    when(descriptor.idempotency()).thenReturn(Idempotency.NON_IDEMPOTENT);
    when(tools.descriptor("external_write")).thenReturn(Optional.of(descriptor));
    when(journal.records("run-1"))
        .thenReturn(
            List.of(
                new ActionRecord(
                    "action-1",
                    "run-1",
                    "external_write",
                    "digest",
                    "plan",
                    ActionStatus.FAILED,
                    1,
                    "timeout",
                    NOW)));
    when(getRun.get("run-1")).thenReturn(failed);

    var recovery = service.inspect("run-1");

    assertEquals("HUMAN_REVIEW_REQUIRED", recovery.replaySafety());
    assertTrue(recovery.allowedActions().contains("HUMAN_REVIEW"));
    assertFalse(recovery.allowedActions().contains("RETRY_CURRENT_STEP"));
  }

  @Test
  void cancelsOnlyTheExpectedVersionAndStopsQueuedCommands() {
    var active = activeRun();
    when(getRun.get("run-1")).thenReturn(active);

    var cancelled = service.cancel("run-1", active.version(), "developer-a", "no longer needed");

    assertEquals(RunStatus.CANCELLED, cancelled.status());
    verify(executions).requestCancellation("run-1");
    verify(commands).cancelQueued("run-1", NOW);
    assertThrows(
        ConcurrentUpdateException.class,
        () -> service.cancel("run-1", active.version() - 1, "developer-a", "stale"));
  }

  @Test
  void exposesWaitForApprovalAndVersionsTheDecision() {
    var waiting =
        activeRun()
            .transitionTo(RunStatus.EXECUTING, NOW, "execute")
            .transitionTo(RunStatus.WAITING_FOR_APPROVAL, NOW, "approval");
    when(getRun.get("run-1")).thenReturn(waiting);

    var recovery = service.inspect("run-1");
    var decided = service.recordDecision("run-1", waiting.version(), "approver-a", "APPROVED");

    assertEquals("POLICY_BLOCK", recovery.failureCategory());
    assertTrue(recovery.allowedActions().contains("WAIT_FOR_APPROVAL"));
    assertTrue(recovery.allowedActions().contains("CANCEL"));
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, decided.status());
  }

  @Test
  void separatesVerificationToolAndInfrastructureFailures() {
    when(getRun.get("run-1")).thenReturn(failedRun());
    trace.record("run-1", "verification.completed", NOW, Map.of("success", "false"));
    assertEquals("VERIFICATION_FAILURE", service.inspect("run-1").failureCategory());

    var toolTrace = new InMemoryTraceSink(new SensitiveDataRedactor(8_192));
    toolTrace.record(
        "run-1", "tool.completed", NOW, Map.of("tool", "compile_project", "status", "FAILED"));
    var toolService = service(toolTrace);
    assertEquals("TOOL_FAILURE", toolService.inspect("run-1").failureCategory());

    when(commands.findByRun("run-1"))
        .thenReturn(List.of(command(RunExecutionCommand.Status.FAILED)));
    assertEquals("INFRASTRUCTURE_FAILURE", toolService.inspect("run-1").failureCategory());
  }

  @Test
  void rejectsCancellationAndRetryWhenAnActionOutcomeIsUncertain() {
    var active = activeRun();
    when(getRun.get("run-1")).thenReturn(active);
    when(journal.records("run-1"))
        .thenReturn(
            List.of(
                new ActionRecord(
                    "action-1",
                    "run-1",
                    "external_write",
                    "digest",
                    "plan",
                    ActionStatus.RUNNING,
                    1,
                    "started",
                    NOW)));

    assertThrows(
        IllegalStateException.class,
        () -> service.cancel("run-1", active.version(), "developer-a", "cancel"));
    assertThrows(
        IllegalStateException.class,
        () -> service.requireAction("run-1", active.version(), "RETRY_CURRENT_STEP"));
  }

  private RunRecoveryService service(InMemoryTraceSink alternativeTrace) {
    return new RunRecoveryService(
        getRun,
        runs,
        commands,
        executions,
        checkpoints,
        journal,
        tools,
        alternativeTrace,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static TaskRun activeRun() {
    return TaskRun.create(new RunId("run-1"), new TaskId("task-1"), NOW)
        .transitionTo(RunStatus.PREPARING_WORKSPACE, NOW, "start")
        .workspaceReady(new WorkspaceId("run-1"), NOW)
        .transitionTo(RunStatus.QUEUED, NOW, "queued")
        .transitionTo(RunStatus.PLANNING, NOW, "planning");
  }

  private static TaskRun failedRun() {
    return activeRun().transitionTo(RunStatus.FAILED, NOW, "agent execution failed");
  }

  private static RunExecutionCommand command() {
    return command(RunExecutionCommand.Status.COMPLETED);
  }

  private static RunExecutionCommand command(RunExecutionCommand.Status status) {
    return new RunExecutionCommand(
        "command-1",
        "run-1",
        RunExecutionCommand.Operation.EXECUTE,
        2,
        "execute-run-1",
        "developer-a",
        "correlation-1",
        status,
        1,
        null,
        null,
        "",
        NOW.minusSeconds(2),
        NOW);
  }
}

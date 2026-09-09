package io.legacypilot.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RunExecutionDispatcherTest {
  private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");
  private final RunExecutionCommandRepository commands = mock(RunExecutionCommandRepository.class);
  private final TaskRunRepository runs = mock(TaskRunRepository.class);
  private final GetRunStatusUseCase getRun = mock(GetRunStatusUseCase.class);
  private final RunExecutionService executions = mock(RunExecutionService.class);
  private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
  private final InMemoryTraceSink trace = new InMemoryTraceSink(new SensitiveDataRedactor(8_192));
  private final AtomicReference<Runnable> submitted = new AtomicReference<>();
  private RunExecutionDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    Executor executor = submitted::set;
    when(commands.add(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(runs.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(scheduler.scheduleWithFixedDelay(any(), any(Long.class), any(Long.class), any()))
        .thenReturn(mock(ScheduledFuture.class));
    dispatcher =
        new RunExecutionDispatcher(
            commands,
            runs,
            getRun,
            executions,
            trace,
            executor,
            scheduler,
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(2),
            Duration.ofSeconds(5));
  }

  @Test
  void acceptsOnceTransitionsToQueuedAndExecutesInTheBackground() {
    var run = workspaceReadyRun();
    when(getRun.get("run-1")).thenReturn(run, run.transitionTo(RunStatus.QUEUED, NOW, "queued"));
    when(commands.findByRunAndIdempotencyKey("run-1", "execute-run-1"))
        .thenReturn(Optional.empty());

    var accepted =
        dispatcher.accept(
            "run-1",
            RunExecutionCommand.Operation.EXECUTE,
            "execute-run-1",
            "developer-a",
            "correlation-1");

    assertEquals(RunExecutionCommand.Status.QUEUED, accepted.status());
    assertEquals("command.accepted", trace.events("run-1").getFirst().type());
    assertEquals(
        "correlation-1", trace.events("run-1").getFirst().attributes().get("correlationId"));
    verify(runs).update(any());
    when(commands.claim(anyString(), anyString(), any(), any())).thenReturn(true);
    when(commands.findById(accepted.id())).thenReturn(Optional.of(accepted));
    submitted.get().run();
    verify(executions).execute("run-1", "developer-a");
    verify(commands).complete(anyString(), anyString(), any());
  }

  @Test
  void returnsTheOriginalCommandForTheSameIdempotencyKey() {
    var existing = command(RunExecutionCommand.Status.COMPLETED);
    when(commands.findByRunAndIdempotencyKey("run-1", "execute-run-1"))
        .thenReturn(Optional.of(existing));

    var accepted =
        dispatcher.accept(
            "run-1",
            RunExecutionCommand.Operation.EXECUTE,
            "execute-run-1",
            "developer-a",
            "correlation-2");

    assertSame(existing, accepted);
    assertEquals(0, trace.events("run-1").size());
    verify(commands, never()).add(any());
  }

  @Test
  void rejectsAnotherExecutionCommandAfterTheRunIsQueued() {
    var queued = workspaceReadyRun().transitionTo(RunStatus.QUEUED, NOW, "queued");
    when(commands.findByRunAndIdempotencyKey("run-1", "another-key")).thenReturn(Optional.empty());
    when(getRun.get("run-1")).thenReturn(queued);

    assertThrows(
        IllegalStateException.class,
        () ->
            dispatcher.accept(
                "run-1",
                RunExecutionCommand.Operation.EXECUTE,
                "another-key",
                "developer-a",
                "correlation-2"));

    verify(commands, never()).add(any());
  }

  @Test
  void resumesAnExpiredClaimFromTheDurableCheckpoint() {
    var command = command(RunExecutionCommand.Status.RUNNING);
    var active = workspaceReadyRun().transitionTo(RunStatus.PLANNING, NOW, "started");
    when(commands.claim(anyString(), anyString(), any(), any())).thenReturn(true);
    when(commands.findById(command.id())).thenReturn(Optional.of(command));
    when(getRun.get("run-1")).thenReturn(active);

    dispatcher.runCommand(command.id());

    verify(executions).recover("run-1");
  }

  @Test
  void resumesAnApprovedRunInTheBackground() {
    var command =
        new RunExecutionCommand(
            "command-2",
            "run-1",
            RunExecutionCommand.Operation.RESUME,
            4,
            "resume-run-1-action-1",
            "developer-a",
            "correlation-2",
            RunExecutionCommand.Status.QUEUED,
            0,
            null,
            null,
            "",
            NOW,
            NOW);
    when(commands.claim(anyString(), anyString(), any(), any())).thenReturn(true);
    when(commands.findById(command.id())).thenReturn(Optional.of(command));
    when(getRun.get("run-1"))
        .thenReturn(
            workspaceReadyRun()
                .transitionTo(RunStatus.QUEUED, NOW, "queued")
                .transitionTo(RunStatus.PLANNING, NOW, "planning")
                .transitionTo(RunStatus.EXECUTING, NOW, "executing")
                .transitionTo(RunStatus.WAITING_FOR_APPROVAL, NOW, "approval required"));

    dispatcher.runCommand(command.id());

    verify(executions).resume("run-1");
  }

  @Test
  void retriesFromTheExpectedTerminalVersionAsANewCommand() {
    var failed =
        workspaceReadyRun()
            .transitionTo(RunStatus.QUEUED, NOW, "queued")
            .transitionTo(RunStatus.PLANNING, NOW, "planning")
            .transitionTo(RunStatus.FAILED, NOW, "model timeout")
            .withVersion(5);
    var recovering = failed.beginRetry(RunStatus.EXECUTING, NOW, "retry");
    when(getRun.get("run-1")).thenReturn(failed, recovering);
    when(commands.findByRunAndIdempotencyKey("run-1", "retry-run-1-5"))
        .thenReturn(Optional.empty());

    var accepted =
        dispatcher.accept(
            "run-1",
            RunExecutionCommand.Operation.RETRY,
            "retry-run-1-5",
            "developer-a",
            "correlation-2",
            5L);

    assertEquals(RunExecutionCommand.Operation.RETRY, accepted.operation());
    when(commands.claim(anyString(), anyString(), any(), any())).thenReturn(true);
    when(commands.findById(accepted.id())).thenReturn(Optional.of(accepted));
    submitted.get().run();
    verify(executions).retry("run-1", false);
  }

  @Test
  void resumesAReopenedRetryCheckpointAfterAWorkerRestart() {
    var retry =
        new RunExecutionCommand(
            "command-retry",
            "run-1",
            RunExecutionCommand.Operation.RETRY,
            5,
            "retry-run-1-5",
            "developer-a",
            "correlation-retry",
            RunExecutionCommand.Status.RUNNING,
            1,
            "old-worker",
            NOW.minusSeconds(1),
            "",
            NOW.minusSeconds(60),
            NOW);
    var reopened = workspaceReadyRun().transitionTo(RunStatus.PLANNING, NOW, "retry reopened");
    when(commands.claim(anyString(), anyString(), any(), any())).thenReturn(true);
    when(commands.findById(retry.id())).thenReturn(Optional.of(retry));
    when(getRun.get("run-1")).thenReturn(reopened);

    dispatcher.runCommand(retry.id());

    verify(executions).recover("run-1");
    verify(executions, never()).retry(anyString(), anyBoolean());
    verify(commands).complete(anyString(), anyString(), any());
  }

  @Test
  void rejectsAStaleBrowserVersionBeforeScheduling() {
    var failed =
        workspaceReadyRun()
            .transitionTo(RunStatus.QUEUED, NOW, "queued")
            .transitionTo(RunStatus.PLANNING, NOW, "planning")
            .transitionTo(RunStatus.FAILED, NOW, "failed")
            .withVersion(6);
    when(getRun.get("run-1")).thenReturn(failed);
    when(commands.findByRunAndIdempotencyKey("run-1", "retry-run-1-5"))
        .thenReturn(Optional.empty());

    assertThrows(
        io.legacypilot.application.error.ConcurrentUpdateException.class,
        () ->
            dispatcher.accept(
                "run-1",
                RunExecutionCommand.Operation.RETRY,
                "retry-run-1-5",
                "developer-a",
                "correlation-2",
                5L));
  }

  private static TaskRun workspaceReadyRun() {
    return TaskRun.create(new RunId("run-1"), new TaskId("task-1"), NOW)
        .transitionTo(RunStatus.PREPARING_WORKSPACE, NOW, "start")
        .workspaceReady(new WorkspaceId("run-1"), NOW);
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
        status == RunExecutionCommand.Status.RUNNING ? "old-worker" : null,
        status == RunExecutionCommand.Status.RUNNING ? NOW.minusSeconds(1) : null,
        "",
        NOW.minusSeconds(60),
        NOW);
  }
}

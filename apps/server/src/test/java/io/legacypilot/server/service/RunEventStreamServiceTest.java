package io.legacypilot.server.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.legacypilot.application.service.GetRunStatusUseCase;
import io.legacypilot.domain.run.RunId;
import io.legacypilot.domain.run.RunStatus;
import io.legacypilot.domain.run.TaskRun;
import io.legacypilot.domain.task.TaskId;
import io.legacypilot.observability.InMemoryTraceSink;
import io.legacypilot.observability.SensitiveDataRedactor;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class RunEventStreamServiceTest {

  @Test
  void sendsReplayThenOnlyNewEventsAndCancelsThePollerOnCompletion() throws Exception {
    var trace = new InMemoryTraceSink(new SensitiveDataRedactor(8_192));
    trace.record("run-1", "run.started", Instant.EPOCH, Map.of());
    var runs = mock(GetRunStatusUseCase.class);
    when(runs.get("run-1")).thenReturn(activeRun());
    var scheduler = mock(ScheduledExecutorService.class);
    var poll = new AtomicReference<Runnable>();
    var future = mock(ScheduledFuture.class);
    when(scheduler.scheduleWithFixedDelay(any(), any(Long.class), any(Long.class), any()))
        .thenAnswer(
            invocation -> {
              poll.set(invocation.getArgument(0));
              return future;
            });
    var completion = new AtomicReference<Runnable>();
    var emitter = mock(SseEmitter.class);
    doAnswer(
            invocation -> {
              completion.set(invocation.getArgument(0));
              return null;
            })
        .when(emitter)
        .onCompletion(any());
    var service = service(trace, runs, scheduler);

    service.stream("run-1", 0, emitter);
    trace.record("run-1", "plan.created", Instant.EPOCH.plusSeconds(1), Map.of("risk", "LOW"));
    poll.get().run();
    completion.get().run();

    verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    verify(future).cancel(false);
  }

  @Test
  void completesTerminalRunsAndConvertsSendFailuresToEmitterErrors() throws Exception {
    var trace = new InMemoryTraceSink(new SensitiveDataRedactor(8_192));
    trace.record("run-1", "run.completed", Instant.EPOCH, Map.of("status", "SUCCEEDED"));
    var runs = mock(GetRunStatusUseCase.class);
    when(runs.get("run-1")).thenReturn(terminalRun());
    var scheduler = mock(ScheduledExecutorService.class);
    when(scheduler.scheduleWithFixedDelay(any(), any(Long.class), any(Long.class), any()))
        .thenReturn(mock(ScheduledFuture.class));
    var successful = mock(SseEmitter.class);

    service(trace, runs, scheduler).stream("run-1", 0, successful);
    verify(successful).complete();

    var failed = mock(SseEmitter.class);
    doThrow(new IOException("client disconnected"))
        .when(failed)
        .send(any(SseEmitter.SseEventBuilder.class));
    service(trace, runs, scheduler).stream("run-1", 0, failed);
    verify(failed).completeWithError(any(IOException.class));
  }

  @Test
  void rejectsInvalidStreamDurations() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RunEventStreamService(
                mock(RunEventFeed.class),
                mock(GetRunStatusUseCase.class),
                mock(ScheduledExecutorService.class),
                Duration.ZERO,
                Duration.ofMinutes(1)));
  }

  private static RunEventStreamService service(
      InMemoryTraceSink trace, GetRunStatusUseCase runs, ScheduledExecutorService scheduler) {
    return new RunEventStreamService(
        new RunEventFeed(trace), runs, scheduler, Duration.ofMillis(500), Duration.ofMinutes(1));
  }

  private static TaskRun activeRun() {
    return TaskRun.create(new RunId("run-1"), new TaskId("task-1"), Instant.EPOCH)
        .transitionTo(RunStatus.PREPARING_WORKSPACE, Instant.EPOCH, "preparing");
  }

  private static TaskRun terminalRun() {
    return activeRun().transitionTo(RunStatus.FAILED, Instant.EPOCH, "failed");
  }
}

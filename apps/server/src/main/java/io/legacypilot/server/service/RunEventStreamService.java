package io.legacypilot.server.service;

import io.legacypilot.application.service.GetRunStatusUseCase;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public final class RunEventStreamService {
  private final RunEventFeed feed;
  private final GetRunStatusUseCase runs;
  private final ScheduledExecutorService scheduler;
  private final Duration pollInterval;
  private final Duration timeout;
  private final Set<SseEmitter> activeEmitters = ConcurrentHashMap.newKeySet();

  public RunEventStreamService(
      RunEventFeed feed,
      GetRunStatusUseCase runs,
      @Qualifier("runEventScheduler") ScheduledExecutorService scheduler,
      @Value("${legacy-pilot.events.poll-interval:500ms}") Duration pollInterval,
      @Value("${legacy-pilot.events.stream-timeout:30m}") Duration timeout) {
    if (pollInterval.isZero() || pollInterval.isNegative() || timeout.isNegative()) {
      throw new IllegalArgumentException("event stream durations must be positive");
    }
    this.feed = feed;
    this.runs = runs;
    this.scheduler = scheduler;
    this.pollInterval = pollInterval;
    this.timeout = timeout;
  }

  public SseEmitter stream(String runId, int lastSequence) {
    return stream(runId, lastSequence, new SseEmitter(timeout.toMillis()));
  }

  SseEmitter stream(String runId, int lastSequence, SseEmitter emitter) {
    runs.get(runId);
    activeEmitters.add(emitter);
    var cursor = new AtomicInteger(lastSequence);
    var scheduled = new AtomicReference<ScheduledFuture<?>>();
    Runnable cancel =
        () -> {
          var future = scheduled.get();
          if (future != null) {
            future.cancel(false);
          }
          activeEmitters.remove(emitter);
        };
    emitter.onCompletion(cancel);
    emitter.onTimeout(cancel);
    emitter.onError(ignored -> cancel.run());

    Runnable pump =
        () -> {
          try {
            for (var event : feed.after(runId, cursor.get())) {
              emitter.send(
                  SseEmitter.event()
                      .id(Integer.toString(event.sequence()))
                      .name("run-event")
                      .reconnectTime(1_500)
                      .data(event));
              cursor.set(event.sequence());
            }
            if (runs.get(runId).status().isTerminal()) {
              emitter.complete();
            }
          } catch (IOException | RuntimeException exception) {
            emitter.completeWithError(exception);
          }
        };
    scheduled.set(
        scheduler.scheduleWithFixedDelay(
            pump, pollInterval.toMillis(), pollInterval.toMillis(), TimeUnit.MILLISECONDS));
    pump.run();
    return emitter;
  }

  /** Closes current streams so the demo profile can exercise browser reconnection behavior. */
  public int disconnectActiveStreams() {
    var disconnected = activeEmitters.size();
    for (var emitter : List.copyOf(activeEmitters)) {
      emitter.complete();
    }
    return disconnected;
  }
}

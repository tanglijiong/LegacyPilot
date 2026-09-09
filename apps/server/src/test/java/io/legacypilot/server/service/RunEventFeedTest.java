package io.legacypilot.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.legacypilot.observability.InMemoryTraceSink;
import io.legacypilot.observability.SensitiveDataRedactor;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunEventFeedTest {

  @Test
  void replaysOnlyMissingEventsInOrderAndCarriesTheCommandCorrelationId() {
    var trace = new InMemoryTraceSink(new SensitiveDataRedactor(8_192));
    trace.record(
        "run-1",
        "command.accepted",
        Instant.EPOCH,
        Map.of("operation", "EXECUTE", "correlationId", "correlation-1"));
    trace.record("run-1", "plan.created", Instant.EPOCH.plusSeconds(1), Map.of("risk", "MEDIUM"));
    trace.record(
        "run-1",
        "tool.completed",
        Instant.EPOCH.plusSeconds(2),
        Map.of("tool", "apply_patch", "status", "APPROVAL_REQUIRED"));

    var missing = new RunEventFeed(trace).after("run-1", 1);

    assertEquals(2, missing.size());
    assertEquals(2, missing.get(0).sequence());
    assertEquals("PLANNING", missing.get(0).phase());
    assertEquals("correlation-1", missing.get(0).correlationId());
    assertEquals("WAITING_FOR_APPROVAL", missing.get(1).status());
  }

  @Test
  void exposesOnlyControlledSummariesInsteadOfTraceAttributeValues() {
    var trace = new InMemoryTraceSink(new SensitiveDataRedactor(8_192));
    trace.record(
        "run-2",
        "action.started",
        Instant.EPOCH,
        Map.of("actionId", "secret-action", "digest", "secret-digest"));

    var event = new RunEventFeed(trace).after("run-2", 0).getFirst();

    assertEquals("Controlled action started", event.summary());
  }

  @Test
  void mapsRuntimeStagesAndSafeSummariesForThePublicContract() {
    var trace = new InMemoryTraceSink(new SensitiveDataRedactor(8_192));
    var types =
        new String[] {
          "lease.acquired",
          "lease.released",
          "run.started",
          "context.compacted",
          "action.replay.skipped",
          "approval.consumed",
          "approval.denied",
          "verification.completed",
          "artifact.verification.completed",
          "model.failed",
          "task-mode.denied",
          "run.completed",
          "unknown.event"
        };
    for (var type : types) {
      trace.record(
          "run-3",
          type,
          Instant.EPOCH,
          Map.of("success", "true", "status", "SUCCEEDED", "type", "TIMEOUT"));
    }

    var events = new RunEventFeed(trace).after("run-3", 0);

    assertEquals(types.length, events.size());
    assertEquals("VERIFYING", events.get(7).phase());
    assertEquals("SUCCEEDED", events.get(11).status());
    assertEquals("unknown event", events.get(12).summary());
  }
}

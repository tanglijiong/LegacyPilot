package io.legacypilot.observability;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface TraceSink {
  void append(TraceEvent event);

  List<TraceEvent> events(String runId);

  default TraceEvent record(
      String runId, String type, Instant occurredAt, Map<String, String> attributes) {
    var event = new TraceEvent(runId, events(runId).size() + 1, type, occurredAt, attributes);
    append(event);
    return event;
  }
}

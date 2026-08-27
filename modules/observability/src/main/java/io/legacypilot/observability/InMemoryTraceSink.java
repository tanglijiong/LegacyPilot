package io.legacypilot.observability;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryTraceSink implements TraceSink {

  private final List<TraceEvent> events = new CopyOnWriteArrayList<>();
  private final SensitiveDataRedactor redactor;

  public InMemoryTraceSink(SensitiveDataRedactor redactor) {
    this.redactor = java.util.Objects.requireNonNull(redactor);
  }

  @Override
  public void append(TraceEvent event) {
    var safe = new java.util.LinkedHashMap<String, String>();
    event.attributes().forEach((key, value) -> safe.put(key, redactor.redact(key, value)));
    events.add(
        new TraceEvent(event.runId(), event.sequence(), event.type(), event.occurredAt(), safe));
  }

  @Override
  public List<TraceEvent> events(String runId) {
    return events.stream()
        .filter(event -> event.runId().equals(runId))
        .sorted(Comparator.comparingInt(TraceEvent::sequence))
        .toList();
  }
}

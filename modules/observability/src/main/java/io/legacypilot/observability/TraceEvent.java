package io.legacypilot.observability;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record TraceEvent(
    String runId, int sequence, String type, Instant occurredAt, Map<String, String> attributes) {

  public TraceEvent {
    Objects.requireNonNull(runId, "runId must not be null");
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    Objects.requireNonNull(attributes, "attributes must not be null");
    if (runId.isBlank() || type.isBlank() || sequence < 1) {
      throw new IllegalArgumentException("trace event identity is invalid");
    }
    attributes = Map.copyOf(attributes);
  }
}

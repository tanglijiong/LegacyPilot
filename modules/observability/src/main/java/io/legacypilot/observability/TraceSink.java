package io.legacypilot.observability;

import java.util.List;

public interface TraceSink {
  void append(TraceEvent event);

  List<TraceEvent> events(String runId);
}

package io.legacypilot.observability;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Objects;

public final class AgentMetrics {

  private final MeterRegistry registry;

  public AgentMetrics(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry);
  }

  public void modelUsage(int tokens) {
    registry.counter("legacypilot.model.tokens").increment(tokens);
  }

  public void toolInvocation(String tool, String status) {
    registry.counter("legacypilot.tool.invocations", "tool", tool, "status", status).increment();
  }

  public void runCompleted(String status, Duration duration) {
    registry.counter("legacypilot.runs", "status", status).increment();
    registry.timer("legacypilot.run.duration", "status", status).record(duration);
  }
}

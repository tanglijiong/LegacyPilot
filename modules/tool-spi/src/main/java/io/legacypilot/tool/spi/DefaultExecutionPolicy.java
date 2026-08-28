package io.legacypilot.tool.spi;

import com.fasterxml.jackson.databind.JsonNode;

public final class DefaultExecutionPolicy implements ExecutionPolicy {
  private final ConfigurableExecutionPolicy delegate =
      new ConfigurableExecutionPolicy(PolicyLoader.secureDefault());

  @Override
  public PolicyDecision evaluate(ToolDescriptor descriptor, ToolContext context, JsonNode input) {
    return delegate.evaluate(descriptor, context, input);
  }
}

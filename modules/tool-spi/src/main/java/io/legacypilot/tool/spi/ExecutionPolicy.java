package io.legacypilot.tool.spi;

import com.fasterxml.jackson.databind.JsonNode;

public interface ExecutionPolicy {
  PolicyDecision evaluate(ToolDescriptor descriptor, ToolContext context, JsonNode input);
}

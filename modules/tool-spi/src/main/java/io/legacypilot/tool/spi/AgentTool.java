package io.legacypilot.tool.spi;

import com.fasterxml.jackson.databind.JsonNode;

public interface AgentTool {
  ToolDescriptor descriptor();

  JsonNode execute(ToolContext context, JsonNode input);
}

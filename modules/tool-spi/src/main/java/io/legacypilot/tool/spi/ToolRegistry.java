package io.legacypilot.tool.spi;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ToolRegistry {

  private final Map<String, AgentTool> tools;

  public ToolRegistry(Collection<? extends AgentTool> tools) {
    var registered = new LinkedHashMap<String, AgentTool>();
    for (var tool : tools) {
      var previous = registered.putIfAbsent(tool.descriptor().name(), tool);
      if (previous != null) {
        throw new IllegalArgumentException("Duplicate tool: " + tool.descriptor().name());
      }
    }
    this.tools = Map.copyOf(registered);
  }

  public Optional<AgentTool> find(String name) {
    return Optional.ofNullable(tools.get(name));
  }

  public List<ToolDescriptor> descriptors() {
    return tools.values().stream().map(AgentTool::descriptor).toList();
  }
}

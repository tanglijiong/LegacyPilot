package io.legacypilot.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

public record AgentAction(String type, String tool, JsonNode input, String reason) {

  public AgentAction {
    Objects.requireNonNull(type);
    tool = Objects.requireNonNullElse(tool, "");
    input =
        input == null ? com.fasterxml.jackson.databind.node.NullNode.instance : input.deepCopy();
    reason = Objects.requireNonNullElse(reason, "");
    if (!java.util.Set.of("TOOL", "VERIFY", "FINISH").contains(type)) {
      throw new IllegalArgumentException("agent action type is invalid");
    }
    if (type.equals("TOOL") && tool.isBlank()) {
      throw new IllegalArgumentException("tool action requires a tool name");
    }
  }

  @Override
  public JsonNode input() {
    return input.deepCopy();
  }
}

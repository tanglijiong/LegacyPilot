package io.legacypilot.tool.spi;

import com.fasterxml.jackson.databind.JsonNode;

public final class DefaultExecutionPolicy implements ExecutionPolicy {

  @Override
  public PolicyDecision evaluate(ToolDescriptor descriptor, ToolContext context, JsonNode input) {
    var digest = ActionDigests.create(descriptor.name(), input);
    if (context.approvedActionDigests().contains(digest)) {
      return new PolicyDecision(PolicyDecision.Effect.ALLOW, "matching action approved", digest);
    }
    return switch (descriptor.risk()) {
      case READ_ONLY -> new PolicyDecision(PolicyDecision.Effect.ALLOW, "read-only tool", digest);
      case WORKSPACE_WRITE ->
          new PolicyDecision(
              PolicyDecision.Effect.REQUIRE_APPROVAL,
              "workspace write requires an action-bound approval",
              digest);
      case COMMAND_EXECUTION ->
          context.commandExecutionAllowed()
              ? new PolicyDecision(
                  PolicyDecision.Effect.ALLOW, "command execution enabled for this run", digest)
              : new PolicyDecision(
                  PolicyDecision.Effect.REQUIRE_APPROVAL,
                  "command execution requires approval",
                  digest);
      case EXTERNAL_IO ->
          new PolicyDecision(PolicyDecision.Effect.DENY, "external I/O is disabled", digest);
    };
  }
}

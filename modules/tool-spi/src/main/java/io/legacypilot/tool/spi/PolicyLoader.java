package io.legacypilot.tool.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class PolicyLoader implements ExecutionPolicy {
  private final ObjectMapper mapper;
  private volatile ConfigurableExecutionPolicy active;
  private volatile String lastError = "";

  public PolicyLoader(PolicyDocument initial) {
    this.mapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
    this.active = new ConfigurableExecutionPolicy(Objects.requireNonNull(initial));
  }

  public synchronized boolean reload(Path source) {
    try {
      if (!Files.isRegularFile(source)) {
        throw new IOException("policy source is not a regular file");
      }
      var candidate = mapper.readValue(source.toFile(), PolicyDocument.class);
      active = new ConfigurableExecutionPolicy(candidate);
      lastError = "";
      return true;
    } catch (IOException | IllegalArgumentException exception) {
      lastError = "policy reload rejected: " + exception.getClass().getSimpleName();
      return false;
    }
  }

  @Override
  public PolicyDecision evaluate(ToolDescriptor descriptor, ToolContext context, JsonNode input) {
    return active.evaluate(descriptor, context, input);
  }

  public PolicyDocument activeDocument() {
    return active.document();
  }

  public String lastError() {
    return lastError;
  }

  public static PolicyDocument secureDefault() {
    return new PolicyDocument(
        PolicyDocument.CURRENT_SCHEMA_VERSION,
        "secure-default-v1",
        List.of(
            rule(
                "deny-external-io",
                PolicyDecision.Effect.DENY,
                java.util.Set.of(RiskLevel.EXTERNAL_IO),
                null,
                "external I/O is disabled",
                ""),
            rule(
                "approve-workspace-write",
                PolicyDecision.Effect.REQUIRE_APPROVAL,
                java.util.Set.of(RiskLevel.WORKSPACE_WRITE),
                null,
                "workspace write requires an action-bound approval",
                "action"),
            rule(
                "allow-command-enabled",
                PolicyDecision.Effect.ALLOW,
                java.util.Set.of(RiskLevel.COMMAND_EXECUTION),
                true,
                "command execution enabled for this run",
                ""),
            rule(
                "approve-command",
                PolicyDecision.Effect.REQUIRE_APPROVAL,
                java.util.Set.of(RiskLevel.COMMAND_EXECUTION),
                false,
                "command execution requires approval",
                "action"),
            rule(
                "allow-read-only",
                PolicyDecision.Effect.ALLOW,
                java.util.Set.of(RiskLevel.READ_ONLY),
                null,
                "read-only tool",
                "")));
  }

  private static PolicyRule rule(
      String id,
      PolicyDecision.Effect effect,
      java.util.Set<RiskLevel> risks,
      Boolean commandAllowed,
      String reason,
      String scope) {
    return new PolicyRule(
        id,
        effect,
        java.util.Set.of(),
        risks,
        java.util.Set.of(),
        java.util.Set.of(),
        commandAllowed,
        0,
        reason,
        scope);
  }
}

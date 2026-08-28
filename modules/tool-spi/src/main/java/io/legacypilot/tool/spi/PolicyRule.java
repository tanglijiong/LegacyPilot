package io.legacypilot.tool.spi;

import java.util.Objects;
import java.util.Set;

public record PolicyRule(
    String id,
    PolicyDecision.Effect effect,
    Set<String> tools,
    Set<RiskLevel> risks,
    Set<Idempotency> idempotencies,
    Set<String> pathPrefixes,
    Boolean commandExecutionAllowed,
    int priority,
    String reason,
    String requiredScope) {

  public PolicyRule {
    Objects.requireNonNull(id, "rule id must not be null");
    Objects.requireNonNull(effect, "rule effect must not be null");
    tools = tools == null ? Set.of() : Set.copyOf(tools);
    risks = risks == null ? Set.of() : Set.copyOf(risks);
    idempotencies = idempotencies == null ? Set.of() : Set.copyOf(idempotencies);
    pathPrefixes = pathPrefixes == null ? Set.of() : Set.copyOf(pathPrefixes);
    reason = Objects.requireNonNullElse(reason, "").strip();
    requiredScope = Objects.requireNonNullElse(requiredScope, "").strip();
    if (!id.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,95}")) {
      throw new IllegalArgumentException("policy rule id is invalid");
    }
    if (tools.stream().anyMatch(tool -> !validToolPattern(tool))) {
      throw new IllegalArgumentException("policy tool pattern is invalid");
    }
    if (pathPrefixes.stream().anyMatch(PolicyRule::unsafePrefix)) {
      throw new IllegalArgumentException("policy path prefix must be a safe relative path");
    }
    if (effect == PolicyDecision.Effect.REQUIRE_APPROVAL && requiredScope.isBlank()) {
      throw new IllegalArgumentException("approval rules require a scope");
    }
  }

  int specificity() {
    return (tools.isEmpty() ? 0 : 8)
        + (risks.isEmpty() ? 0 : 4)
        + (idempotencies.isEmpty() ? 0 : 2)
        + (pathPrefixes.isEmpty() ? 0 : 4)
        + (commandExecutionAllowed == null ? 0 : 1);
  }

  private static boolean validToolPattern(String value) {
    return value != null
        && (value.equals("*")
            || value.matches("[a-z][a-z0-9_.-]{1,95}")
            || value.matches("[a-z][a-z0-9_.-]{0,94}\\.\\*"));
  }

  private static boolean unsafePrefix(String value) {
    if (value == null || value.isBlank() || value.startsWith("/") || value.startsWith("\\")) {
      return true;
    }
    return java.nio.file.Path.of(value).normalize().startsWith("..");
  }
}

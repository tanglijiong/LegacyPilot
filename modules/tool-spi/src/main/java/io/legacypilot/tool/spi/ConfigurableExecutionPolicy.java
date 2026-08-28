package io.legacypilot.tool.spi;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class ConfigurableExecutionPolicy implements ExecutionPolicy {
  private static final List<String> PATH_FIELDS =
      List.of("path", "source", "target", "from", "to", "file");

  private final PolicyDocument document;

  public ConfigurableExecutionPolicy(PolicyDocument document) {
    this.document = Objects.requireNonNull(document);
  }

  @Override
  public PolicyDecision evaluate(ToolDescriptor descriptor, ToolContext context, JsonNode input) {
    var digest = ActionDigests.create(descriptor.name(), input);
    var matching =
        document.rules().stream()
            .filter(rule -> matches(rule, descriptor, context, input))
            .sorted(ruleOrder())
            .toList();
    if (matching.isEmpty()) {
      return decision(
          PolicyDecision.Effect.DENY,
          "no policy rule matched; denied by default",
          digest,
          "builtin.fail-closed",
          "");
    }
    var selected = matching.getFirst();
    if (selected.effect() == PolicyDecision.Effect.REQUIRE_APPROVAL
        && context.approvedActionDigests().contains(digest)) {
      return decision(
          PolicyDecision.Effect.ALLOW,
          "matching action approved: " + selected.reason(),
          digest,
          selected.id(),
          selected.requiredScope());
    }
    return decision(
        selected.effect(),
        selected.reason().isBlank() ? "matched policy rule " + selected.id() : selected.reason(),
        digest,
        selected.id(),
        selected.requiredScope());
  }

  public PolicyDocument document() {
    return document;
  }

  private PolicyDecision decision(
      PolicyDecision.Effect effect,
      String reason,
      String digest,
      String ruleId,
      String requiredScope) {
    return new PolicyDecision(effect, reason, digest, ruleId, document.revision(), requiredScope);
  }

  private static Comparator<PolicyRule> ruleOrder() {
    return Comparator.comparingInt((PolicyRule rule) -> effectRank(rule.effect()))
        .reversed()
        .thenComparing(Comparator.comparingInt(PolicyRule::specificity).reversed())
        .thenComparing(Comparator.comparingInt(PolicyRule::priority).reversed())
        .thenComparing(PolicyRule::id);
  }

  private static int effectRank(PolicyDecision.Effect effect) {
    return switch (effect) {
      case DENY -> 3;
      case REQUIRE_APPROVAL -> 2;
      case ALLOW -> 1;
    };
  }

  private static boolean matches(
      PolicyRule rule, ToolDescriptor descriptor, ToolContext context, JsonNode input) {
    return matchesTool(rule.tools(), descriptor.name())
        && (rule.risks().isEmpty() || rule.risks().contains(descriptor.risk()))
        && (rule.idempotencies().isEmpty()
            || rule.idempotencies().contains(descriptor.idempotency()))
        && (rule.commandExecutionAllowed() == null
            || rule.commandExecutionAllowed() == context.commandExecutionAllowed())
        && matchesPaths(rule.pathPrefixes(), input);
  }

  private static boolean matchesTool(java.util.Set<String> patterns, String tool) {
    if (patterns.isEmpty()) {
      return true;
    }
    return patterns.stream()
        .anyMatch(
            pattern ->
                pattern.equals("*")
                    || pattern.equals(tool)
                    || (pattern.endsWith(".*")
                        && tool.startsWith(pattern.substring(0, pattern.length() - 1))));
  }

  private static boolean matchesPaths(java.util.Set<String> prefixes, JsonNode input) {
    if (prefixes.isEmpty()) {
      return true;
    }
    var paths = new ArrayList<Path>();
    if (input != null && input.isObject()) {
      for (var field : PATH_FIELDS) {
        var value = input.path(field);
        if (value.isTextual()) {
          var path = Path.of(value.asText());
          if (path.isAbsolute() || path.normalize().startsWith("..")) {
            return false;
          }
          paths.add(path.normalize());
        }
      }
    }
    return !paths.isEmpty()
        && paths.stream()
            .allMatch(
                path ->
                    prefixes.stream()
                        .map(Path::of)
                        .map(Path::normalize)
                        .anyMatch(path::startsWith));
  }
}

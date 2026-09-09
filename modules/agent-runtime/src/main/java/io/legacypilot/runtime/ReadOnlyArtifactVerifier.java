package io.legacypilot.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.legacypilot.domain.task.TaskMode;
import io.legacypilot.observability.SourceEvidence;
import io.legacypilot.observability.TaskArtifact;
import io.legacypilot.tool.spi.ToolContext;
import io.legacypilot.tool.spi.ToolExecutor;
import io.legacypilot.verification.RiskLevel;
import io.legacypilot.verification.VerificationEvidence;
import io.legacypilot.verification.VerificationOutcome;
import io.legacypilot.verification.VerificationStatus;
import io.legacypilot.verification.WorkspaceIntegrityCheck;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministically validates source grounding and the no-write contract for read-only tasks. */
public final class ReadOnlyArtifactVerifier {

  private static final Map<TaskMode, Set<String>> REQUIRED_SECTIONS =
      Map.of(
          TaskMode.ANALYZE,
          Set.of(
              "entryPoints",
              "callPaths",
              "affectedComponents",
              "businessRules",
              "dataOperations",
              "risks",
              "recommendedTests"),
          TaskMode.EXPLAIN,
          Set.of(
              "actors",
              "triggers",
              "mainFlow",
              "decisionRules",
              "stateChanges",
              "dataChanges",
              "exceptionFlows",
              "glossary"));

  private final ObjectMapper mapper;

  public ReadOnlyArtifactVerifier(ObjectMapper mapper) {
    this.mapper = java.util.Objects.requireNonNull(mapper);
  }

  public TaskArtifact parse(AgentRunRequest request, JsonNode submitted) {
    var mode = request.mode();
    if (!mode.readOnly() || submitted == null || !submitted.isObject()) {
      throw new IllegalArgumentException("a read-only task result object is required");
    }
    var sections = new LinkedHashMap<String, List<String>>();
    var evidence = new ArrayList<SourceEvidence>();
    submitted
        .path("sections")
        .fields()
        .forEachRemaining(
            field -> {
              if (!field.getValue().isArray()) {
                throw new IllegalArgumentException("artifact section values must be arrays");
              }
              var values = new ArrayList<String>();
              field
                  .getValue()
                  .forEach(
                      value -> {
                        if (value.isObject()) {
                          var claim = value.path("claim").asText("");
                          if (claim.isBlank()) {
                            throw new IllegalArgumentException("section claim must not be blank");
                          }
                          var symbol =
                              resolveSymbol(request, value.path("symbol").asText(""), claim);
                          values.add(claim);
                          evidence.add(
                              new SourceEvidence(
                                  symbol.path(),
                                  symbol.qualifiedName(),
                                  symbol.range().start().line(),
                                  symbol.range().end().line(),
                                  canonicalExcerpt(request, symbol),
                                  claim,
                                  request.projectIndex().revision()));
                        } else {
                          values.add(value.asText());
                        }
                      });
              sections.put(field.getKey(), values);
            });
    submitted
        .path("evidence")
        .forEach(value -> evidence.add(mapper.convertValue(value, SourceEvidence.class)));
    var unknowns = new ArrayList<String>();
    submitted.path("unknowns").forEach(value -> unknowns.add(value.asText()));
    return new TaskArtifact(
        TaskArtifact.CURRENT_SCHEMA_VERSION,
        mode.name(),
        submitted.path("summary").asText(),
        sections,
        evidence,
        unknowns);
  }

  private static String canonicalExcerpt(
      AgentRunRequest request, io.legacypilot.analysis.java.SourceSymbol symbol) {
    var root = request.workspace().toAbsolutePath().normalize();
    var relative = Path.of(symbol.path()).normalize();
    var file = root.resolve(relative).normalize();
    try {
      if (relative.isAbsolute()
          || relative.startsWith("..")
          || !file.startsWith(root)
          || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
          || Files.isSymbolicLink(file)) {
        throw new IllegalArgumentException("invalid indexed source path: " + symbol.path());
      }
      var lines = Files.readAllLines(file);
      var start = symbol.range().start().line();
      var end = symbol.range().end().line();
      if (start < 1 || end < start || end > lines.size()) {
        throw new IllegalArgumentException("invalid indexed source range: " + symbol.path());
      }
      return String.join("\n", lines.subList(start - 1, end));
    } catch (IOException exception) {
      throw new IllegalArgumentException(
          "unable to read indexed source: " + symbol.path(), exception);
    }
  }

  private static io.legacypilot.analysis.java.SourceSymbol resolveSymbol(
      AgentRunRequest request, String reference, String claim) {
    var exact =
        request.projectIndex().symbols().stream()
            .filter(
                symbol ->
                    symbol.id().equals(reference)
                        || symbol.qualifiedName().equals(reference)
                        || symbol.signature().equals(reference))
            .toList();
    if (exact.size() == 1 && !exact.getFirst().sourceText().isBlank()) {
      return exact.getFirst();
    }
    var simple =
        request.projectIndex().symbols().stream()
            .filter(symbol -> symbol.simpleName().equals(reference))
            .filter(symbol -> !symbol.sourceText().isBlank())
            .toList();
    if (simple.size() == 1) {
      return simple.getFirst();
    }
    var namedInClaim =
        request.projectIndex().symbols().stream()
            .filter(symbol -> !symbol.testSource())
            .filter(symbol -> !symbol.sourceText().isBlank())
            .filter(symbol -> claim.contains(symbol.simpleName()))
            .sorted(
                java.util.Comparator.comparingInt(
                        (io.legacypilot.analysis.java.SourceSymbol symbol) ->
                            symbol.simpleName().length())
                    .reversed()
                    .thenComparing(io.legacypilot.analysis.java.SourceSymbol::qualifiedName))
            .toList();
    if (!namedInClaim.isEmpty()) {
      return namedInClaim.getFirst();
    }
    throw new IllegalArgumentException("unknown or ambiguous source symbol: " + reference);
  }

  public Result verify(AgentRunRequest request, TaskArtifact artifact, ToolExecutor tools) {
    var checks = new ArrayList<VerificationEvidence>();
    var toolContext = new ToolContext(request.runId(), request.workspace(), Set.of(), true);
    checks.add(
        new WorkspaceIntegrityCheck()
            .verify(
                new io.legacypilot.verification.VerificationContext(
                    request.workspace(), toolContext, tools)));
    checks.add(noDiff(tools, toolContext));

    var required = REQUIRED_SECTIONS.getOrDefault(request.mode(), Set.of());
    var missing =
        required.stream()
            .filter(name -> artifact.sections().getOrDefault(name, List.of()).isEmpty())
            .sorted()
            .toList();
    checks.add(
        evidence(
            "required-sections",
            missing.isEmpty() ? VerificationStatus.PASSED : VerificationStatus.FAILED,
            missing.isEmpty()
                ? "All required sections are present"
                : "Missing sections: " + missing,
            true));

    var invalidEvidence = validateEvidence(request, artifact.evidence());
    checks.add(
        evidence(
            "source-evidence",
            invalidEvidence.isEmpty() ? VerificationStatus.PASSED : VerificationStatus.FAILED,
            invalidEvidence.isEmpty()
                ? artifact.evidence().size() + " source references are valid"
                : String.join("; ", invalidEvidence),
            true));

    var claims =
        artifact.evidence().stream()
            .map(SourceEvidence::claim)
            .collect(java.util.stream.Collectors.toSet());
    var statements =
        artifact.sections().values().stream()
            .flatMap(List::stream)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    var unsupported = statements.stream().filter(statement -> !claims.contains(statement)).toList();
    checks.add(
        evidence(
            "claim-grounding",
            unsupported.isEmpty() && !statements.isEmpty()
                ? VerificationStatus.PASSED
                : VerificationStatus.FAILED,
            unsupported.isEmpty()
                ? statements.size() + " statements are grounded"
                : "Statements without matching evidence claims: " + unsupported,
            true));

    var responseLanguageMatches = ResponseLanguage.matches(request, artifact);
    checks.add(
        evidence(
            "response-language",
            responseLanguageMatches ? VerificationStatus.PASSED : VerificationStatus.FAILED,
            responseLanguageMatches
                ? "Response language matches the current user question"
                : "Current question is Chinese; rewrite all user-facing result text in Simplified Chinese",
            true));

    var outcome = new VerificationOutcome(checks, risk(checks));
    var metrics =
        Map.of(
            "evidenceCount", Integer.toString(artifact.evidence().size()),
            "statementCount", Integer.toString(statements.size()),
            "unsupportedClaimCount", Integer.toString(unsupported.size()),
            "responseLanguageMatch", Boolean.toString(responseLanguageMatches),
            "writeViolationCount", noDiffPassed(checks) ? "0" : "1");
    return new Result(outcome, metrics);
  }

  private static VerificationEvidence noDiff(ToolExecutor tools, ToolContext context) {
    var result = tools.execute("git_diff", context, JsonNodeFactory.instance.objectNode());
    if (!result.successful()) {
      return evidence(
          "read-only-diff", VerificationStatus.FAILED, "Unable to inspect Git diff", false);
    }
    var clean =
        result.output().path("numstat").asText("").isBlank()
            && result.output().path("status").asText("").isBlank();
    return evidence(
        "read-only-diff",
        clean ? VerificationStatus.PASSED : VerificationStatus.BLOCKED,
        clean ? "No workspace changes detected" : "Read-only task changed the workspace",
        false);
  }

  private static List<String> validateEvidence(
      AgentRunRequest request, List<SourceEvidence> evidence) {
    if (evidence.isEmpty()) {
      return List.of("At least one source reference is required");
    }
    var problems = new ArrayList<String>();
    var root = request.workspace().toAbsolutePath().normalize();
    for (var item : evidence) {
      try {
        var relative = Path.of(item.path()).normalize();
        var file = root.resolve(relative).normalize();
        if (relative.isAbsolute()
            || relative.startsWith("..")
            || !file.startsWith(root)
            || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(file)) {
          problems.add("Invalid evidence path: " + item.path());
          continue;
        }
        var sourceLines = Files.readAllLines(file);
        if (item.endLine() > sourceLines.size()) {
          problems.add("Evidence line range exceeds file: " + item.path());
          continue;
        }
        var selectedSource =
            String.join("\n", sourceLines.subList(item.startLine() - 1, item.endLine()));
        if (!selectedSource.contains(item.excerpt())) {
          problems.add("Evidence excerpt was not found in the stated line range: " + item.path());
        }
        if (!item.revision().equals(request.projectIndex().revision())) {
          problems.add("Evidence revision mismatch: " + item.path());
        }
        var symbolMatches =
            request.projectIndex().symbols().stream()
                .filter(symbol -> symbol.path().equals(relative.toString()))
                .anyMatch(
                    symbol ->
                        (symbol.id().equals(item.symbol())
                                || symbol.simpleName().equals(item.symbol())
                                || symbol.qualifiedName().equals(item.symbol())
                                || symbol.signature().equals(item.symbol()))
                            && symbol.range().start().line() <= item.endLine()
                            && symbol.range().end().line() >= item.startLine());
        if (!symbolMatches) {
          problems.add("Evidence symbol was not indexed: " + item.symbol());
        }
      } catch (IOException | RuntimeException exception) {
        problems.add("Unable to validate evidence: " + item.path());
      }
    }
    return problems;
  }

  private static boolean noDiffPassed(List<VerificationEvidence> checks) {
    return checks.stream()
        .filter(item -> item.name().equals("read-only-diff"))
        .allMatch(item -> item.status() == VerificationStatus.PASSED);
  }

  private static VerificationEvidence evidence(
      String name, VerificationStatus status, String summary, boolean repairable) {
    return new VerificationEvidence(
        name, status, true, repairable, "", null, summary, "", Duration.ZERO);
  }

  private static RiskLevel risk(List<VerificationEvidence> checks) {
    if (checks.stream().anyMatch(item -> item.status() == VerificationStatus.BLOCKED)) {
      return RiskLevel.BLOCKED;
    }
    return checks.stream().allMatch(item -> item.status() == VerificationStatus.PASSED)
        ? RiskLevel.LOW
        : RiskLevel.HIGH;
  }

  public record Result(VerificationOutcome outcome, Map<String, String> metrics) {
    public Result {
      metrics = Map.copyOf(metrics);
    }
  }
}

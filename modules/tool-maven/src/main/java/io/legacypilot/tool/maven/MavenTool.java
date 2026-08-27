package io.legacypilot.tool.maven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.sandbox.DockerSandbox;
import io.legacypilot.sandbox.SandboxExecutor;
import io.legacypilot.sandbox.SandboxLimits;
import io.legacypilot.sandbox.SandboxRequest;
import io.legacypilot.sandbox.SandboxStatus;
import io.legacypilot.tool.spi.AgentTool;
import io.legacypilot.tool.spi.Idempotency;
import io.legacypilot.tool.spi.JsonSchemas;
import io.legacypilot.tool.spi.RiskLevel;
import io.legacypilot.tool.spi.ToolContext;
import io.legacypilot.tool.spi.ToolDescriptor;
import io.legacypilot.tool.spi.ToolErrorCode;
import io.legacypilot.tool.spi.ToolFailureException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MavenTool implements AgentTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SAFE_ARGUMENT = "[A-Za-z0-9_.:-]{1,160}";

  private final ToolDescriptor descriptor;
  private final SandboxExecutor sandbox;
  private final Path dependencyCache;
  private final List<String> goals;
  private final boolean requiresTestClass;
  private final Set<String> allowedProfiles;
  private final Set<String> allowedProperties;
  private final SandboxLimits limits;

  private MavenTool(
      String name,
      String description,
      SandboxExecutor sandbox,
      Path dependencyCache,
      List<String> goals,
      boolean requiresTestClass,
      Set<String> allowedProfiles,
      Set<String> allowedProperties,
      SandboxLimits limits) {
    this.sandbox = sandbox;
    this.dependencyCache = dependencyCache;
    this.goals = List.copyOf(goals);
    this.requiresTestClass = requiresTestClass;
    this.allowedProfiles = Set.copyOf(allowedProfiles);
    this.allowedProperties = Set.copyOf(allowedProperties);
    this.limits = limits;
    var required = requiresTestClass ? "[\"testClass\"]" : "[]";
    this.descriptor =
        new ToolDescriptor(
            name,
            description,
            JsonSchemas.parse(
                """
                {"type":"object","required":%s,"additionalProperties":false,"properties":{
                 "testClass":{"type":"string","maxLength":256},
                 "profiles":{"type":"array","maxItems":10,"items":{"type":"string","maxLength":80}},
                 "properties":{"type":"object"}}}
                """
                    .replace("%s", required)),
            JsonSchemas.parse("{\"type\":\"object\"}"),
            RiskLevel.COMMAND_EXECUTION,
            Idempotency.CONDITIONAL,
            limits.timeout().plusSeconds(5),
            64 * 1024,
            limits.maxOutputBytes() + 64 * 1024,
            Set.of());
  }

  public static MavenTool compile(
      SandboxExecutor sandbox,
      Path cache,
      Set<String> profiles,
      Set<String> properties,
      SandboxLimits limits) {
    return new MavenTool(
        "compile_project",
        "Compile the project in the offline Docker sandbox.",
        sandbox,
        cache,
        List.of("compile"),
        false,
        profiles,
        properties,
        limits);
  }

  public static MavenTool tests(
      SandboxExecutor sandbox,
      Path cache,
      Set<String> profiles,
      Set<String> properties,
      SandboxLimits limits) {
    return new MavenTool(
        "run_tests",
        "Run the project test suite in the offline Docker sandbox.",
        sandbox,
        cache,
        List.of("test"),
        false,
        profiles,
        properties,
        limits);
  }

  public static MavenTool testClass(
      SandboxExecutor sandbox,
      Path cache,
      Set<String> profiles,
      Set<String> properties,
      SandboxLimits limits) {
    return new MavenTool(
        "run_test_class",
        "Run one allowlisted test selector in the offline Docker sandbox.",
        sandbox,
        cache,
        List.of("test"),
        true,
        profiles,
        properties,
        limits);
  }

  public static MavenTool staticAnalysis(
      SandboxExecutor sandbox,
      Path cache,
      Set<String> profiles,
      Set<String> properties,
      SandboxLimits limits) {
    return new MavenTool(
        "static_analysis",
        "Run configured Checkstyle and SpotBugs checks in the offline Docker sandbox.",
        sandbox,
        cache,
        List.of("checkstyle:check", "spotbugs:check"),
        false,
        profiles,
        properties,
        limits);
  }

  @Override
  public ToolDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public JsonNode execute(ToolContext context, JsonNode input) {
    var command = command(input);
    var executionId = context.runId() + "-" + UUID.randomUUID();
    var request =
        new SandboxRequest(
            executionId,
            DockerSandbox.DEFAULT_MAVEN_IMAGE,
            context.workspaceRoot(),
            dependencyCache,
            command,
            Map.of(),
            limits);
    var result = sandbox.execute(request);
    if (result.status() != SandboxStatus.SUCCESS) {
      var code =
          result.status() == SandboxStatus.TIMED_OUT
              ? ToolErrorCode.TIMEOUT
              : ToolErrorCode.COMMAND_FAILED;
      throw new ToolFailureException(code, result.output());
    }
    var output = MAPPER.createObjectNode();
    output.put("executionId", result.executionId());
    output.put("status", result.status().name());
    if (result.exitCode() == null) {
      output.putNull("exitCode");
    } else {
      output.put("exitCode", result.exitCode());
    }
    output.put("output", result.output());
    output.put("durationMillis", result.duration().toMillis());
    output.put("truncated", result.outputTruncated());
    return output;
  }

  List<String> command(JsonNode input) {
    var command = new ArrayList<String>();
    command.add("mvn");
    command.add("--batch-mode");
    command.add("--no-transfer-progress");
    command.add("--offline");
    if (dependencyCache != null) {
      command.add("-Dmaven.repo.local=/maven-cache");
    }
    var profiles = input.path("profiles");
    if (profiles.isArray() && !profiles.isEmpty()) {
      var requested = new ArrayList<String>();
      profiles.forEach(value -> requested.add(value.asText()));
      if (!allowedProfiles.containsAll(requested) || requested.stream().anyMatch(this::unsafe)) {
        throw invalid("Maven profile is not allowlisted");
      }
      command.add("-P" + String.join(",", requested));
    }
    var properties = input.path("properties");
    if (properties.isObject()) {
      var values = new java.util.TreeMap<String, String>();
      properties
          .properties()
          .forEach(entry -> values.put(entry.getKey(), entry.getValue().asText()));
      values.forEach(
          (key, value) -> {
            if (!allowedProperties.contains(key) || unsafe(key) || unsafe(value)) {
              throw invalid("Maven property is not allowlisted");
            }
            command.add("-D" + key + "=" + value);
          });
    }
    if (requiresTestClass) {
      var testClass = input.path("testClass").asText();
      if (unsafe(testClass)) {
        throw invalid("Test class selector is invalid");
      }
      command.add("-Dtest=" + testClass);
    }
    command.addAll(goals);
    return List.copyOf(command);
  }

  private boolean unsafe(String value) {
    return value == null || !value.matches(SAFE_ARGUMENT) || value.startsWith("-");
  }

  private static ToolFailureException invalid(String message) {
    return new ToolFailureException(ToolErrorCode.INVALID_INPUT_SCHEMA, message);
  }
}

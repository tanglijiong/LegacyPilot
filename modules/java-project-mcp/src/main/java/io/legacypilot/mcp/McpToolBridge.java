package io.legacypilot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.observability.TraceSink;
import io.legacypilot.runtime.ActionJournal;
import io.legacypilot.runtime.ActionRecord;
import io.legacypilot.runtime.ActionStatus;
import io.legacypilot.runtime.CapabilityService;
import io.legacypilot.runtime.CapabilityUse;
import io.legacypilot.runtime.RunLeaseStore;
import io.legacypilot.tool.spi.ActionDigests;
import io.legacypilot.tool.spi.RiskLevel;
import io.legacypilot.tool.spi.ToolContext;
import io.legacypilot.tool.spi.ToolExecutor;
import io.legacypilot.tool.spi.ToolRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class McpToolBridge {
  private static final Map<String, String> EXPOSED =
      Map.of(
          "project.search_code", "search_code",
          "project.find_references", "find_references",
          "project.read_file", "read_file",
          "project.apply_patch", "apply_patch",
          "git.diff", "git_diff",
          "maven.compile_project", "compile_project",
          "maven.run_tests", "run_tests");

  private final String sessionId;
  private final Path workspace;
  private final ToolRegistry registry;
  private final ToolExecutor executor;
  private final TraceSink trace;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final CapabilityService capabilities;
  private final ActionJournal journal;
  private final RunLeaseStore leases;

  public McpToolBridge(
      String sessionId,
      Path workspace,
      ToolRegistry registry,
      ToolExecutor executor,
      TraceSink trace,
      ObjectMapper mapper,
      Clock clock) {
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("MCP session id must not be blank");
    }
    this.sessionId = sessionId;
    this.workspace = Objects.requireNonNull(workspace).toAbsolutePath().normalize();
    this.registry = Objects.requireNonNull(registry);
    this.executor = Objects.requireNonNull(executor);
    this.trace = Objects.requireNonNull(trace);
    this.mapper = Objects.requireNonNull(mapper);
    this.clock = Objects.requireNonNull(clock);
    this.capabilities = null;
    this.journal = null;
    this.leases = null;
  }

  public McpToolBridge(
      String sessionId,
      Path workspace,
      ToolRegistry registry,
      ToolExecutor executor,
      TraceSink trace,
      ObjectMapper mapper,
      Clock clock,
      CapabilityService capabilities,
      ActionJournal journal,
      RunLeaseStore leases) {
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("MCP session id must not be blank");
    }
    this.sessionId = sessionId;
    this.workspace = Objects.requireNonNull(workspace).toAbsolutePath().normalize();
    this.registry = Objects.requireNonNull(registry);
    this.executor = Objects.requireNonNull(executor);
    this.trace = Objects.requireNonNull(trace);
    this.mapper = Objects.requireNonNull(mapper);
    this.clock = Objects.requireNonNull(clock);
    this.capabilities = Objects.requireNonNull(capabilities);
    this.journal = Objects.requireNonNull(journal);
    this.leases = Objects.requireNonNull(leases);
  }

  public JsonNode listTools() {
    var tools = mapper.createArrayNode();
    EXPOSED.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              var found = registry.find(entry.getValue());
              if (found.isEmpty()) {
                return;
              }
              var descriptor = found.orElseThrow().descriptor();
              var schema =
                  descriptor.risk() == RiskLevel.WORKSPACE_WRITE
                      ? writeCallSchema(descriptor.inputSchema())
                      : descriptor.inputSchema();
              tools
                  .addObject()
                  .put("name", entry.getKey())
                  .put("description", descriptor.description())
                  .set("inputSchema", schema);
            });
    return tools;
  }

  public JsonNode call(String externalName, JsonNode arguments) {
    var internal = EXPOSED.get(externalName);
    if (internal == null) {
      return response(null, "MCP tool is not exposed", true, "UNREGISTERED_TOOL");
    }
    var descriptor = registry.find(internal).map(value -> value.descriptor()).orElse(null);
    if (descriptor == null) {
      return response(null, "MCP tool is unavailable", true, "UNREGISTERED_TOOL");
    }
    if (descriptor.risk() == RiskLevel.WORKSPACE_WRITE) {
      return callWrite(externalName, internal, arguments);
    }
    var context = new ToolContext(sessionId, workspace, Set.of(), true);
    var result = executor.execute(internal, context, arguments);
    record(externalName, internal, result);
    return toolResponse(result);
  }

  private JsonNode callWrite(String externalName, String internal, JsonNode arguments) {
    if (capabilities == null || journal == null || leases == null) {
      return response(null, "MCP write capabilities are disabled", true, "CAPABILITY_REQUIRED");
    }
    var authorization = arguments == null ? mapper.missingNode() : arguments.path("authorization");
    var input = arguments == null ? mapper.missingNode() : arguments.path("input");
    var token = authorization.path("token").asText("");
    var subject = authorization.path("subject").asText("");
    var runId = authorization.path("runId").asText("");
    var planDigest = authorization.path("planDigest").asText("");
    if (token.isBlank() || subject.isBlank() || runId.isBlank() || input.isMissingNode()) {
      return response(null, "A scoped capability is required", true, "CAPABILITY_REQUIRED");
    }
    var actionDigest = ActionDigests.create(internal, input);
    var lease =
        leases.acquire(runId, sessionId, clock.instant(), Duration.ofMinutes(1)).orElse(null);
    if (lease == null) {
      return response(null, "The run is owned by another executor", true, "LEASE_CONFLICT");
    }
    try {
      var actionId = "mcp-" + actionDigest.substring(0, 24);
      var existing = journal.find(runId, actionId).orElse(null);
      var authorized =
          capabilities.consume(
              token,
              new CapabilityUse(
                  subject, sessionId, runId, internal, workspace, actionDigest, planDigest));
      if (authorized.isEmpty()) {
        return response(
            null,
            "Capability is invalid, expired, consumed, or out of scope",
            true,
            "CAPABILITY_DENIED");
      }
      if (existing != null
          && existing.status() == ActionStatus.SUCCEEDED
          && !confirmedPatchEffect(internal, input)) {
        existing =
            existing.transition(
                ActionStatus.NEEDS_REVIEW,
                existing.attempts(),
                "recorded success no longer matches workspace effect",
                clock.instant());
        journal.save(existing);
      }
      if (existing != null && existing.status() == ActionStatus.SUCCEEDED) {
        var replay = mapper.createObjectNode().put("replayed", true).put("actionId", actionId);
        return response(replay, "Previously successful action was not executed again", false, "");
      }
      if (existing != null
          && (existing.status() == ActionStatus.RUNNING
              || existing.status() == ActionStatus.NEEDS_REVIEW)) {
        if (existing.status() != ActionStatus.NEEDS_REVIEW) {
          journal.save(
              existing.transition(
                  ActionStatus.NEEDS_REVIEW,
                  existing.attempts(),
                  "uncertain MCP write requires review",
                  clock.instant()));
        }
        return response(null, "Uncertain write requires human review", true, "NEEDS_REVIEW");
      }
      var prepared =
          new ActionRecord(
              actionId,
              runId,
              internal,
              actionDigest,
              planDigest,
              ActionStatus.PREPARED,
              existing == null ? 0 : existing.attempts(),
              "capability validated",
              clock.instant());
      journal.save(prepared);
      var running =
          prepared.transition(
              ActionStatus.RUNNING,
              prepared.attempts() + 1,
              "MCP tool invocation started",
              clock.instant());
      journal.save(running);
      var result =
          executor.execute(
              internal, new ToolContext(sessionId, workspace, Set.of(actionDigest), false), input);
      var summary =
          result.successful()
              ? Objects.toString(result.output(), "tool succeeded")
              : result.error().code() + ": " + result.error().message();
      journal.save(
          running.transition(
              result.successful() ? ActionStatus.SUCCEEDED : ActionStatus.FAILED,
              running.attempts(),
              summary,
              clock.instant()));
      record(externalName, internal, result);
      return toolResponse(result);
    } finally {
      leases.release(lease);
    }
  }

  private void record(
      String externalName, String internal, io.legacypilot.tool.spi.ToolResult result) {
    var attributes = new LinkedHashMap<String, String>();
    attributes.put("mcpTool", externalName);
    attributes.put("internalTool", internal);
    attributes.put("status", result.status().name());
    attributes.put("actionDigest", result.actionDigest());
    attributes.put("policyRuleId", result.policyRuleId());
    attributes.put("policyRevision", result.policyRevision());
    trace.record(sessionId, "mcp.tool.completed", clock.instant(), attributes);
  }

  private JsonNode toolResponse(io.legacypilot.tool.spi.ToolResult result) {
    return result.successful()
        ? response(
            result.output(),
            result.output() == null ? "completed" : result.output().toString(),
            false,
            "")
        : response(null, result.error().message(), true, result.error().code().name());
  }

  private JsonNode writeCallSchema(JsonNode toolSchema) {
    var schema = mapper.createObjectNode();
    schema.put("type", "object");
    schema.putArray("required").add("authorization").add("input");
    schema.put("additionalProperties", false);
    var properties = schema.putObject("properties");
    properties
        .putObject("authorization")
        .put("type", "object")
        .putArray("required")
        .add("token")
        .add("subject")
        .add("runId");
    properties.set("input", toolSchema);
    return schema;
  }

  private boolean confirmedPatchEffect(String internal, JsonNode input) {
    if (!internal.equals("apply_patch")) {
      return true;
    }
    try {
      var relative = Path.of(input.path("path").asText()).normalize();
      var target = workspace.resolve(relative).normalize();
      return !relative.isAbsolute()
          && !relative.startsWith("..")
          && target.startsWith(workspace)
          && java.nio.file.Files.isRegularFile(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)
          && java.nio.file.Files.readString(target).equals(input.path("replacement").asText());
    } catch (RuntimeException | java.io.IOException exception) {
      return false;
    }
  }

  private JsonNode response(JsonNode structured, String text, boolean error, String code) {
    var response = mapper.createObjectNode();
    response.put("isError", error);
    response.putArray("content").addObject().put("type", "text").put("text", text);
    if (structured != null) {
      response.set("structuredContent", structured);
    }
    if (!code.isBlank()) {
      response.put("errorCode", code);
    }
    return response;
  }
}

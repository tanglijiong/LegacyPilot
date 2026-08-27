package io.legacypilot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.observability.TraceEvent;
import io.legacypilot.observability.TraceSink;
import io.legacypilot.tool.spi.ToolContext;
import io.legacypilot.tool.spi.ToolExecutor;
import io.legacypilot.tool.spi.ToolRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class McpToolBridge {
  private static final Map<String, String> EXPOSED =
      Map.of(
          "project.search_code", "search_code",
          "project.find_references", "find_references",
          "project.read_file", "read_file",
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
  private final AtomicInteger sequence = new AtomicInteger();

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
  }

  public JsonNode listTools() {
    var tools = mapper.createArrayNode();
    EXPOSED.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              var descriptor = registry.find(entry.getValue()).orElseThrow().descriptor();
              tools
                  .addObject()
                  .put("name", entry.getKey())
                  .put("description", descriptor.description())
                  .set("inputSchema", descriptor.inputSchema());
            });
    return tools;
  }

  public JsonNode call(String externalName, JsonNode arguments) {
    var internal = EXPOSED.get(externalName);
    if (internal == null) {
      return response(null, "MCP tool is not exposed", true, "UNREGISTERED_TOOL");
    }
    var context = new ToolContext(sessionId, workspace, Set.of(), true);
    var result = executor.execute(internal, context, arguments);
    var attributes = new LinkedHashMap<String, String>();
    attributes.put("mcpTool", externalName);
    attributes.put("internalTool", internal);
    attributes.put("status", result.status().name());
    attributes.put("actionDigest", result.actionDigest());
    trace.append(
        new TraceEvent(
            sessionId,
            sequence.incrementAndGet(),
            "mcp.tool.completed",
            clock.instant(),
            attributes));
    return result.successful()
        ? response(
            result.output(),
            result.output() == null ? "completed" : result.output().toString(),
            false,
            "")
        : response(null, result.error().message(), true, result.error().code().name());
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

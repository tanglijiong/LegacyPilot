package io.legacypilot.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.observability.InMemoryTraceSink;
import io.legacypilot.observability.SensitiveDataRedactor;
import io.legacypilot.tool.filesystem.ReadFileTool;
import io.legacypilot.tool.spi.AgentTool;
import io.legacypilot.tool.spi.DefaultExecutionPolicy;
import io.legacypilot.tool.spi.Idempotency;
import io.legacypilot.tool.spi.JsonSchemas;
import io.legacypilot.tool.spi.RiskLevel;
import io.legacypilot.tool.spi.ToolContext;
import io.legacypilot.tool.spi.ToolDescriptor;
import io.legacypilot.tool.spi.ToolExecutor;
import io.legacypilot.tool.spi.ToolRegistry;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpServerTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  @TempDir Path workspace;
  private InMemoryTraceSink trace;
  private McpToolBridge bridge;

  @BeforeEach
  void setUp() throws Exception {
    Files.writeString(workspace.resolve("README.md"), "banking fixture");
    var tools = new ArrayList<AgentTool>();
    tools.add(new ReadFileTool());
    tools.add(stub("search_code"));
    tools.add(stub("find_references"));
    tools.add(stub("git_diff"));
    tools.add(stub("compile_project"));
    tools.add(stub("run_tests"));
    var registry = new ToolRegistry(tools);
    var executor = new ToolExecutor(registry, new DefaultExecutionPolicy(), MAPPER);
    trace = new InMemoryTraceSink(new SensitiveDataRedactor(512));
    bridge =
        new McpToolBridge(
            "mcp-test",
            workspace,
            registry,
            executor,
            trace,
            MAPPER,
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
  }

  @Test
  void exposesOnlySixReadAndVerificationTools() {
    var listed = bridge.listTools();
    assertEquals(6, listed.size());
    assertTrue(listed.toString().contains("project.read_file"));
    assertFalse(listed.toString().contains("apply_patch"));
    assertTrue(
        bridge.call("project.write_file", MAPPER.createObjectNode()).path("isError").asBoolean());
  }

  @Test
  void bindsCallsToWorkspaceAndUsesSharedToolPolicyAndTrace() {
    var success =
        bridge.call("project.read_file", MAPPER.createObjectNode().put("path", "README.md"));
    var escape =
        bridge.call("project.read_file", MAPPER.createObjectNode().put("path", "../outside.txt"));

    assertFalse(success.path("isError").asBoolean());
    assertTrue(success.path("structuredContent").path("content").asText().contains("banking"));
    assertTrue(escape.path("isError").asBoolean());
    assertEquals("PATH_VIOLATION", escape.path("errorCode").asText());
    assertEquals(2, trace.events("mcp-test").size());
  }

  @Test
  void implementsInitializeListCallAndJsonRpcErrors() throws Exception {
    var call =
        "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"project.read_file\","
            + "\"arguments\":{\"path\":\"README.md\"}}}\n";
    var input =
        """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
        {"jsonrpc":"2.0","method":"notifications/initialized"}
        {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
        """
            + call
            + """
                not-json
                {"jsonrpc":"2.0","id":4,"method":"missing"}
                """;
    var output = new StringWriter();
    new McpStdioServer(
            new BufferedReader(new StringReader(input)), new PrintWriter(output), bridge, MAPPER)
        .serve();

    var responses = output.toString().lines().map(this::json).toList();
    assertEquals(5, responses.size());
    assertEquals(
        McpStdioServer.PROTOCOL_VERSION,
        responses.getFirst().at("/result/protocolVersion").asText());
    assertEquals(6, responses.get(1).at("/result/tools").size());
    assertEquals(-32700, responses.get(3).at("/error/code").asInt());
    assertEquals(-32601, responses.get(4).at("/error/code").asInt());
  }

  private JsonNode json(String value) {
    try {
      return MAPPER.readTree(value);
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }

  private static AgentTool stub(String name) {
    return new AgentTool() {
      @Override
      public ToolDescriptor descriptor() {
        return new ToolDescriptor(
            name,
            "MCP test tool",
            JsonSchemas.parse("{\"type\":\"object\"}"),
            JsonSchemas.parse("{\"type\":\"object\"}"),
            RiskLevel.READ_ONLY,
            Idempotency.IDEMPOTENT,
            Duration.ofSeconds(1),
            1024,
            4096,
            Set.of());
      }

      @Override
      public JsonNode execute(ToolContext context, JsonNode input) {
        return MAPPER.createObjectNode().put("ok", true);
      }
    };
  }
}

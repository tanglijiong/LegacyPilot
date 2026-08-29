package io.legacypilot.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.analysis.java.JavaProjectIndexer;
import io.legacypilot.context.DeterministicEmbeddingProvider;
import io.legacypilot.context.FileVectorStore;
import io.legacypilot.context.PersistentVectorRetriever;
import io.legacypilot.context.VectorIndexer;
import io.legacypilot.model.FakeModelGateway;
import io.legacypilot.model.ModelErrorType;
import io.legacypilot.model.ModelException;
import io.legacypilot.model.ModelGateway;
import io.legacypilot.model.ModelProfile;
import io.legacypilot.model.ModelRequest;
import io.legacypilot.model.ModelResult;
import io.legacypilot.model.ModelRoutingBudget;
import io.legacypilot.model.ProviderCircuitBreaker;
import io.legacypilot.model.RoutingModelGateway;
import io.legacypilot.observability.InMemoryTraceSink;
import io.legacypilot.observability.SensitiveDataRedactor;
import io.legacypilot.runtime.ActionStatus;
import io.legacypilot.runtime.CapabilityRequest;
import io.legacypilot.runtime.CapabilityService;
import io.legacypilot.runtime.InMemoryActionJournal;
import io.legacypilot.runtime.InMemoryCapabilityGrantStore;
import io.legacypilot.runtime.InMemoryRunLeaseStore;
import io.legacypilot.tool.filesystem.ApplyPatchTool;
import io.legacypilot.tool.filesystem.ReadFileTool;
import io.legacypilot.tool.spi.ActionDigests;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
    assertEquals("development", responses.getFirst().at("/result/serverInfo/version").asText());
    assertEquals(6, responses.get(1).at("/result/tools").size());
    assertEquals(-32700, responses.get(3).at("/error/code").asInt());
    assertEquals(-32601, responses.get(4).at("/error/code").asInt());
  }

  @Test
  void writeToolRequiresScopedCapabilityConsumesItAndNeverReplaysPatch() throws Exception {
    var source = workspace.resolve("src/main/App.java");
    Files.createDirectories(source.getParent());
    Files.writeString(source, "before");
    var tools = new ArrayList<AgentTool>();
    tools.add(new ApplyPatchTool(java.util.List.of("src/**")));
    var registry = new ToolRegistry(tools);
    var executor = new ToolExecutor(registry, new DefaultExecutionPolicy(), MAPPER);
    var clock = Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneOffset.UTC);
    var localTrace = new InMemoryTraceSink(new SensitiveDataRedactor(512));
    var capabilities = new CapabilityService(new InMemoryCapabilityGrantStore(), localTrace, clock);
    var journal = new InMemoryActionJournal();
    var secureBridge =
        new McpToolBridge(
            "mcp-test",
            workspace,
            registry,
            executor,
            localTrace,
            MAPPER,
            clock,
            capabilities,
            journal,
            new InMemoryRunLeaseStore());
    var input =
        MAPPER
            .createObjectNode()
            .put("path", "src/main/App.java")
            .put("expectedSha256", sha256("before"))
            .put("replacement", "after");
    assertEquals(1, secureBridge.listTools().size());
    assertEquals(
        "CAPABILITY_REQUIRED",
        secureBridge.call("project.apply_patch", input).path("errorCode").asText());
    var issued =
        capabilities.issue(
            new CapabilityRequest(
                "alice",
                "mcp-test",
                "run-write",
                "apply_patch",
                workspace,
                ActionDigests.create("apply_patch", input),
                "",
                clock.instant().plusSeconds(60),
                1));
    var arguments = MAPPER.createObjectNode();
    arguments.set(
        "authorization",
        MAPPER
            .createObjectNode()
            .put("token", issued.token())
            .put("subject", "alice")
            .put("runId", "run-write"));
    arguments.set("input", input);
    var success = secureBridge.call("project.apply_patch", arguments);
    assertFalse(success.path("isError").asBoolean());
    assertEquals("after", Files.readString(source));
    assertEquals(ActionStatus.SUCCEEDED, journal.records("run-write").getFirst().status());

    Files.writeString(source, "externally-changed");
    var replay = secureBridge.call("project.apply_patch", arguments);
    assertEquals("CAPABILITY_DENIED", replay.path("errorCode").asText());
    assertEquals("externally-changed", Files.readString(source));
    var fresh =
        capabilities.issue(
            new CapabilityRequest(
                "alice",
                "mcp-test",
                "run-write",
                "apply_patch",
                workspace,
                ActionDigests.create("apply_patch", input),
                "",
                clock.instant().plusSeconds(60),
                1));
    arguments.withObject("authorization").put("token", fresh.token());
    var changedEffect = secureBridge.call("project.apply_patch", arguments);
    assertEquals("NEEDS_REVIEW", changedEffect.path("errorCode").asText());
    assertEquals("externally-changed", Files.readString(source));

    var routingEvents = new ArrayList<io.legacypilot.model.ModelRouteEvent>();
    var routed =
        new RoutingModelGateway(
            List.of(
                new ModelProfile("primary", "p1", "model-a", Set.of(), 20),
                new ModelProfile("fallback", "p2", "model-b", Set.of(), 10)),
            Map.of(
                "p1",
                failingModel(),
                "p2",
                new FakeModelGateway(List.of(new Answer("safe")), MAPPER)),
            new ModelRoutingBudget(2, 100, BigDecimal.ONE),
            new ProviderCircuitBreaker(1, Duration.ofMinutes(1), clock),
            routingEvents::add,
            clock);
    assertEquals("safe", routed.generate(modelRequest(), Answer.class).value().value());
    assertEquals(2, routingEvents.size());

    var fixture = Path.of("../..", "samples", "banking-demo").toAbsolutePath().normalize();
    var index = new JavaProjectIndexer().index(fixture, "governed-mcp-e2e");
    var embeddings = new DeterministicEmbeddingProvider("hash-v1", 64);
    var vectors =
        new FileVectorStore(workspace.resolve("vectors.json"), MAPPER.findAndRegisterModules());
    new VectorIndexer(embeddings, vectors).synchronize(index);
    var retrieval =
        new PersistentVectorRetriever(embeddings, vectors)
            .retrieveWithStatus(index, "TransferService daily limit", 10);
    assertFalse(retrieval.degraded());
    assertFalse(retrieval.candidates().isEmpty());
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

  private static String sha256(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static ModelGateway failingModel() {
    return new ModelGateway() {
      @Override
      public <T> ModelResult<T> generate(ModelRequest request, Class<T> responseType) {
        throw new ModelException(ModelErrorType.TIMEOUT, "transient", true);
      }
    };
  }

  private static ModelRequest modelRequest() {
    return new ModelRequest(
        "system",
        "user",
        JsonSchemas.parse("{\"type\":\"object\"}"),
        "ignored",
        0,
        Duration.ofSeconds(1),
        Map.of());
  }

  record Answer(String value) {}
}

package io.legacypilot.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.legacypilot.analysis.java.ProjectIndex;
import io.legacypilot.context.ContextBuilder;
import io.legacypilot.context.ContextRequest;
import io.legacypilot.model.FakeModelGateway;
import io.legacypilot.observability.AgentMetrics;
import io.legacypilot.observability.InMemoryTraceSink;
import io.legacypilot.observability.SensitiveDataRedactor;
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
import io.legacypilot.verification.VerificationEvidence;
import io.legacypilot.verification.VerificationPipeline;
import io.legacypilot.verification.VerificationStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentRuntimeTest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());
  @TempDir Path workspace;

  @Test
  void onlyVerificationCanDeclareSuccess() {
    var models =
        new FakeModelGateway(
            List.of(plan(), new AgentAction("FINISH", "", null, "looks complete")), MAPPER);
    var runtime = runtime(models, new InMemoryCheckpointStore(), new InMemoryApprovalStore());

    var result = runtime.execute(request("success-run", 5));

    assertEquals(RuntimeStatus.SUCCEEDED, result.checkpoint().status());
    assertTrue(result.verification().successful());
    assertEquals("LOW", result.report().risk());
  }

  @Test
  void pausesPersistsApprovalAndResumesAfterRestart() {
    var invocations = new AtomicInteger();
    var input = MAPPER.createObjectNode().put("path", "src/Main.java");
    var action = new AgentAction("TOOL", "write_file", input, "apply change");
    var models =
        new FakeModelGateway(
            List.of(plan(), action, new AgentAction("VERIFY", "", null, "verify")), MAPPER);
    var checkpointDir = workspace.resolve("state/checkpoints");
    var approvalFile = workspace.resolve("state/approvals.json");
    var checkpoints = new FileCheckpointStore(checkpointDir, MAPPER);
    var approvals = new FileApprovalStore(approvalFile, MAPPER);

    var paused =
        runtime(models, checkpoints, approvals, writeTool(invocations))
            .execute(request("resume-run", 5));
    assertEquals(RuntimeStatus.WAITING_FOR_APPROVAL, paused.checkpoint().status());
    assertEquals(0, invocations.get());

    var digest = ActionDigests.create(action.tool(), action.input());
    var planDigest =
        ActionDigests.create("change_plan", MAPPER.valueToTree(paused.checkpoint().plan()));
    new FileApprovalStore(approvalFile, MAPPER)
        .save(
            new RuntimeApproval(
                "resume-run",
                digest,
                planDigest,
                "reviewer",
                RuntimeApproval.Decision.APPROVED,
                ApprovalScope.ONCE,
                "reviewed",
                Instant.now().plusSeconds(60)));

    var resumed =
        runtime(
                models,
                new FileCheckpointStore(checkpointDir, MAPPER),
                new FileApprovalStore(approvalFile, MAPPER),
                writeTool(invocations))
            .execute(request("resume-run", 5));

    assertEquals(RuntimeStatus.SUCCEEDED, resumed.checkpoint().status());
    assertEquals(1, invocations.get());
    assertTrue(
        new FileApprovalStore(approvalFile, MAPPER)
            .consumeMatching("resume-run", digest, planDigest, Instant.now())
            .isEmpty());
  }

  @Test
  void enforcesBudgetAndDenial() {
    var readAction = new AgentAction("TOOL", "read_file", MAPPER.createObjectNode(), "inspect");
    var budgetModels = new FakeModelGateway(List.of(plan(), readAction), MAPPER);
    var budget = runtime(budgetModels, new InMemoryCheckpointStore(), new InMemoryApprovalStore());
    assertEquals(
        RuntimeStatus.BUDGET_EXHAUSTED,
        budget.execute(request("budget-run", 1)).checkpoint().status());

    var input = MAPPER.createObjectNode().put("path", "pom.xml");
    var deniedAction = new AgentAction("TOOL", "write_file", input, "change");
    var deniedModels = new FakeModelGateway(List.of(plan(), deniedAction), MAPPER);
    var deniedApprovals = new InMemoryApprovalStore();
    var deniedCheckpoints = new InMemoryCheckpointStore();
    var first =
        runtime(deniedModels, deniedCheckpoints, deniedApprovals).execute(request("deny-run", 5));
    var digest = ActionDigests.create("write_file", input);
    var planDigest =
        ActionDigests.create("change_plan", MAPPER.valueToTree(first.checkpoint().plan()));
    deniedApprovals.save(
        new RuntimeApproval(
            "deny-run",
            digest,
            planDigest,
            "owner",
            RuntimeApproval.Decision.DENIED,
            ApprovalScope.ONCE,
            "too risky",
            Instant.now().plusSeconds(60)));
    assertEquals(
        RuntimeStatus.DENIED,
        runtime(deniedModels, deniedCheckpoints, deniedApprovals)
            .execute(request("deny-run", 5))
            .checkpoint()
            .status());
  }

  @Test
  void persistsRunRequestsForProcessSafeResume() {
    var directory = workspace.resolve("request-store");
    var store = new FileAgentRunRequestStore(directory, MAPPER);
    var request = request("stored-run", 5);

    store.save(request);

    var restored = new FileAgentRunRequestStore(directory, MAPPER).load("stored-run").orElseThrow();
    assertEquals(request.runId(), restored.runId());
    assertEquals(request.workspace(), restored.workspace());
    assertEquals(request.budget(), restored.budget());
    assertTrue(store.load("missing-run").isEmpty());
    assertThrows(IllegalArgumentException.class, () -> store.load("../escape"));
  }

  private AgentRuntime runtime(
      FakeModelGateway models,
      CheckpointStore checkpoints,
      ApprovalStore approvals,
      AgentTool... extra) {
    var tools = new java.util.ArrayList<AgentTool>();
    tools.add(readTool());
    tools.add(extra.length == 0 ? writeTool(new AtomicInteger()) : extra[0]);
    var executor = new ToolExecutor(new ToolRegistry(tools), new DefaultExecutionPolicy(), MAPPER);
    var verification =
        new VerificationPipeline(
            List.of(
                context ->
                    new VerificationEvidence(
                        "tests",
                        VerificationStatus.PASSED,
                        true,
                        true,
                        "run_tests",
                        0,
                        "tests passed",
                        "",
                        Duration.ofMillis(1))));
    return new AgentRuntime(
        new AgentPlanner(models, MAPPER),
        ContextBuilder.defaults(),
        executor,
        verification,
        checkpoints,
        approvals,
        new InMemoryTraceSink(new SensitiveDataRedactor(512)),
        new AgentMetrics(new SimpleMeterRegistry()),
        MAPPER,
        Clock.systemUTC());
  }

  private AgentRunRequest request(String runId, int steps) {
    return new AgentRunRequest(
        runId,
        "make a safe change",
        workspace,
        new ProjectIndex(
            ProjectIndex.CURRENT_SCHEMA_VERSION, "HEAD", List.of(), List.of(), List.of()),
        new ContextRequest("safe change", 100, 10, 1),
        new RuntimeBudget(steps, 2, 1_000, new BigDecimal("1"), Duration.ofMinutes(1)),
        "fake");
  }

  private static ChangePlan plan() {
    return new ChangePlan(
        1, List.of("inspect", "change", "verify"), List.of("src/Main.java"), "LOW", "safe");
  }

  private static AgentTool readTool() {
    return new StubTool(
        "read_file", RiskLevel.READ_ONLY, input -> MAPPER.createObjectNode().put("ok", true));
  }

  private static AgentTool writeTool(AtomicInteger invocations) {
    return new StubTool(
        "write_file",
        RiskLevel.WORKSPACE_WRITE,
        input -> {
          invocations.incrementAndGet();
          return MAPPER.createObjectNode().put("changed", true);
        });
  }

  private record StubTool(String name, RiskLevel risk, Operation operation) implements AgentTool {
    @Override
    public ToolDescriptor descriptor() {
      return new ToolDescriptor(
          name,
          "runtime test tool",
          JsonSchemas.parse("{\"type\":\"object\"}"),
          JsonSchemas.parse("{\"type\":\"object\"}"),
          risk,
          Idempotency.IDEMPOTENT,
          Duration.ofSeconds(1),
          1024,
          4096,
          Set.of());
    }

    @Override
    public JsonNode execute(ToolContext context, JsonNode input) {
      return operation.apply(input);
    }
  }

  private interface Operation {
    JsonNode apply(JsonNode input);
  }
}

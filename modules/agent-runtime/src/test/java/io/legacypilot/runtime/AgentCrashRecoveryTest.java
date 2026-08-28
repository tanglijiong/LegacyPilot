package io.legacypilot.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.analysis.java.ProjectIndex;
import io.legacypilot.context.ContextBuilder;
import io.legacypilot.context.ContextCompactor;
import io.legacypilot.context.ContextRequest;
import io.legacypilot.context.InMemoryTaskMemoryStore;
import io.legacypilot.context.TokenEstimator;
import io.legacypilot.model.FakeModelGateway;
import io.legacypilot.model.ModelUsage;
import io.legacypilot.observability.AgentMetrics;
import io.legacypilot.observability.FileReportStore;
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

class AgentCrashRecoveryTest {
  @TempDir Path temporary;
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void uncertainConditionalActionStopsForReviewThenResumesWithApproval() {
    var fixture = fixture("review-run", ActionStatus.RUNNING, "tool invocation started");

    var paused = fixture.runtime().execute(fixture.request());

    assertEquals(RuntimeStatus.NEEDS_REVIEW, paused.checkpoint().status());
    assertEquals(0, fixture.invocations().get());
    fixture.approve();

    var resumed = fixture.runtime().execute(fixture.request());

    assertEquals(RuntimeStatus.SUCCEEDED, resumed.checkpoint().status());
    assertEquals(1, fixture.invocations().get());
    assertEquals(
        ActionStatus.SUCCEEDED,
        fixture.journal().records(fixture.request().runId()).getFirst().status());
  }

  @Test
  void confirmedSuccessfulActionIsNotRepeatedAfterCrash() {
    var fixture = fixture("skip-run", ActionStatus.SUCCEEDED, "already changed");

    var result = fixture.runtime().execute(fixture.request());

    assertEquals(RuntimeStatus.SUCCEEDED, result.checkpoint().status());
    assertEquals(0, fixture.invocations().get());
    assertEquals(1, result.checkpoint().steps());
  }

  @Test
  void recoveryCoordinatorDoesNotAutoRunApprovalOrReviewStates() {
    var checkpoints = new FileCheckpointStore(temporary.resolve("coordinator"), mapper);
    checkpoints.save(checkpoint("approval-run", RuntimeStatus.WAITING_FOR_APPROVAL));
    checkpoints.save(checkpoint("review-run", RuntimeStatus.NEEDS_REVIEW));
    checkpoints.save(checkpoint("done-run", RuntimeStatus.SUCCEEDED));

    var outcomes =
        new RecoveryCoordinator(
                checkpoints, fixture("unused-run", ActionStatus.SUCCEEDED, "unused").runtime())
            .recoverAll();

    assertEquals(3, outcomes.size());
    assertEquals(
        Set.of(
            RecoveryOutcome.Decision.AWAITING_APPROVAL,
            RecoveryOutcome.Decision.NEEDS_REVIEW,
            RecoveryOutcome.Decision.TERMINAL),
        outcomes.stream()
            .map(RecoveryOutcome::decision)
            .collect(java.util.stream.Collectors.toSet()));
  }

  private AgentCheckpoint checkpoint(String runId, RuntimeStatus status) {
    var now = Instant.now();
    return new AgentCheckpoint(
        runId, status, null, 0, 0, ModelUsage.NONE, now, now, null, "", 0, status.name());
  }

  private Fixture fixture(String runId, ActionStatus status, String summary) {
    var input = mapper.createObjectNode().put("path", "src/Main.java");
    var action = new AgentAction("TOOL", "conditional_change", input, "change once");
    var plan =
        new ChangePlan(1, List.of("change", "verify"), List.of("src/Main.java"), "LOW", "safe");
    var request =
        new AgentRunRequest(
            runId,
            "make a safe change",
            temporary,
            new ProjectIndex(
                ProjectIndex.CURRENT_SCHEMA_VERSION, "HEAD", List.of(), List.of(), List.of()),
            new ContextRequest("safe change", 200, 10, 1),
            new RuntimeBudget(5, 2, 1_000, BigDecimal.ONE, Duration.ofMinutes(2)),
            "fake");
    var checkpoints = new FileCheckpointStore(temporary.resolve(runId + "/checkpoints"), mapper);
    checkpoints.save(
        new AgentCheckpoint(
            runId,
            RuntimeStatus.EXECUTING,
            plan,
            0,
            0,
            ModelUsage.NONE,
            Instant.now(),
            Instant.now(),
            action,
            "",
            0,
            "action selected"));
    var requests = new FileAgentRunRequestStore(temporary.resolve(runId + "/requests"), mapper);
    var approvals = new InMemoryApprovalStore();
    var journal = new FileActionJournal(temporary.resolve(runId + "/actions"), mapper);
    var digest = ActionDigests.create(action.tool(), action.input());
    var planDigest = ActionDigests.create("change_plan", mapper.valueToTree(plan));
    var actionId = "000001-" + digest.substring(0, 12);
    journal.save(
        new ActionRecord(
            actionId, runId, action.tool(), digest, planDigest, status, 1, summary, Instant.now()));
    var invocations = new AtomicInteger();
    var tool = new ConditionalTool(invocations);
    var executor =
        new ToolExecutor(new ToolRegistry(List.of(tool)), new DefaultExecutionPolicy(), mapper);
    var verification =
        new VerificationPipeline(
            List.of(
                ignored ->
                    new VerificationEvidence(
                        "tests",
                        VerificationStatus.PASSED,
                        true,
                        true,
                        "run_tests",
                        0,
                        "passed",
                        "",
                        Duration.ZERO)));
    var models =
        new FakeModelGateway(
            List.of(new AgentAction("VERIFY", "", null, "verify recovered change")), mapper);
    var runtime =
        new AgentRuntime(
            new AgentPlanner(models, mapper),
            ContextBuilder.defaults(),
            executor,
            verification,
            checkpoints,
            requests,
            approvals,
            new InMemoryTraceSink(new SensitiveDataRedactor(512)),
            new FileReportStore(temporary.resolve(runId + "/reports"), mapper),
            new AgentMetrics(new SimpleMeterRegistry()),
            mapper,
            Clock.systemUTC(),
            journal,
            new InMemoryRunLeaseStore(),
            new InMemoryTaskMemoryStore(100),
            new ContextCompactor(TokenEstimator.conservative()),
            "test-owner",
            Duration.ofMinutes(1));
    return new Fixture(
        request, runtime, approvals, journal, action, digest, planDigest, invocations);
  }

  private record Fixture(
      AgentRunRequest request,
      AgentRuntime runtime,
      InMemoryApprovalStore approvals,
      FileActionJournal journal,
      AgentAction action,
      String digest,
      String planDigest,
      AtomicInteger invocations) {
    void approve() {
      approvals.save(
          new RuntimeApproval(
              request.runId(),
              digest,
              planDigest,
              "reviewer",
              RuntimeApproval.Decision.APPROVED,
              ApprovalScope.ONCE,
              "uncertain action reviewed",
              Instant.now().plusSeconds(60)));
    }
  }

  private record ConditionalTool(AtomicInteger invocations) implements AgentTool {
    @Override
    public ToolDescriptor descriptor() {
      return new ToolDescriptor(
          "conditional_change",
          "conditionally idempotent test change",
          JsonSchemas.parse("{\"type\":\"object\"}"),
          JsonSchemas.parse("{\"type\":\"object\"}"),
          RiskLevel.WORKSPACE_WRITE,
          Idempotency.CONDITIONAL,
          Duration.ofSeconds(1),
          1_024,
          4_096,
          Set.of());
    }

    @Override
    public JsonNode execute(ToolContext context, JsonNode input) {
      invocations.incrementAndGet();
      return mapperNode();
    }

    private static JsonNode mapperNode() {
      return new ObjectMapper().createObjectNode().put("changed", true);
    }
  }
}

package io.legacypilot.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.analysis.java.JavaProjectIndexer;
import io.legacypilot.context.ContextBuilder;
import io.legacypilot.context.ContextCompactor;
import io.legacypilot.context.ContextRequest;
import io.legacypilot.context.TokenEstimator;
import io.legacypilot.model.FakeModelGateway;
import io.legacypilot.observability.AgentMetrics;
import io.legacypilot.observability.FileReportStore;
import io.legacypilot.observability.FileTraceSink;
import io.legacypilot.observability.SensitiveDataRedactor;
import io.legacypilot.runtime.ActionJournal;
import io.legacypilot.runtime.ActionRecord;
import io.legacypilot.runtime.ActionStatus;
import io.legacypilot.runtime.AgentAction;
import io.legacypilot.runtime.AgentPlanner;
import io.legacypilot.runtime.AgentRunRequest;
import io.legacypilot.runtime.AgentRuntime;
import io.legacypilot.runtime.ApprovalScope;
import io.legacypilot.runtime.ChangePlan;
import io.legacypilot.runtime.CheckpointStore;
import io.legacypilot.runtime.FileActionJournal;
import io.legacypilot.runtime.FileAgentRunRequestStore;
import io.legacypilot.runtime.FileApprovalStore;
import io.legacypilot.runtime.FileCheckpointStore;
import io.legacypilot.runtime.FileRunLeaseStore;
import io.legacypilot.runtime.FileTaskMemoryStore;
import io.legacypilot.runtime.RuntimeApproval;
import io.legacypilot.runtime.RuntimeBudget;
import io.legacypilot.runtime.RuntimeStatus;
import io.legacypilot.tool.filesystem.ApplyPatchTool;
import io.legacypilot.tool.spi.ActionDigests;
import io.legacypilot.tool.spi.DefaultExecutionPolicy;
import io.legacypilot.tool.spi.ToolExecutor;
import io.legacypilot.tool.spi.ToolRegistry;
import io.legacypilot.verification.RiskLevel;
import io.legacypilot.verification.VerificationEvidence;
import io.legacypilot.verification.VerificationPipeline;
import io.legacypilot.verification.VerificationStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class DailyLimitAgentE2ETest {

  @Test
  void replayAgentChangesTheBaselineAndPassesRealFixtureTests() throws Exception {
    var root = Path.of("../..").toAbsolutePath().normalize();
    try (var workspace = FixtureWorkspace.copyOf(root.resolve("samples/banking-demo"))) {
      var mapper = new ObjectMapper().findAndRegisterModules();
      var reference = root.resolve("evals/reference-solutions/task-005");
      var servicePath = "src/main/java/io/legacypilot/samples/banking/TransferService.java";
      var policyPath = "src/main/java/io/legacypilot/samples/banking/DailyTransferPolicy.java";
      var exceptionPath =
          "src/main/java/io/legacypilot/samples/banking/TransferLimitException.java";
      var testPath = "src/test/java/io/legacypilot/samples/banking/TransferServiceTest.java";
      var plan =
          new ChangePlan(
              1,
              List.of("add policy", "add exception", "update service", "add tests", "verify"),
              List.of(policyPath, exceptionPath, servicePath, testPath),
              "MEDIUM",
              "Daily limit requires service and deterministic tests");
      var actions =
          List.of(
              patch(mapper, workspace.root(), reference, policyPath),
              patch(mapper, workspace.root(), reference, exceptionPath),
              patch(mapper, workspace.root(), reference, servicePath),
              patch(mapper, workspace.root(), reference, testPath),
              new AgentAction("VERIFY", "", null, "run deterministic verification"));
      var script = new java.util.ArrayList<Object>();
      script.add(plan);
      script.addAll(actions);
      var stateRoot = workspace.root().resolve(".agent-state");
      var planDigest = ActionDigests.create("change_plan", mapper.valueToTree(plan));
      var registry = new ToolRegistry(List.of(new ApplyPatchTool(List.of("src/**"))));
      var tools = new ToolExecutor(registry, new DefaultExecutionPolicy(), mapper);
      var task = new EvalDatasetLoader().load(root.resolve("evals/datasets/v0.1")).getLast();
      var verifier = new MavenFixtureVerifier(root.resolve("mvnw"), Duration.ofMinutes(2));
      var verification =
          new VerificationPipeline(
              List.of(
                  context -> {
                    var assertions =
                        new SourceAssertionEngine()
                            .evaluate(context.workspace(), task.assertions());
                    var build = verifier.verify(context.workspace());
                    var passed = assertions.successful() && build.testsPassed();
                    return new VerificationEvidence(
                        "daily-limit-fixture",
                        passed ? VerificationStatus.PASSED : VerificationStatus.FAILED,
                        true,
                        true,
                        "mvn test",
                        passed ? 0 : 1,
                        passed ? "all assertions and tests passed" : build.summary(),
                        "",
                        Duration.ZERO);
                  }));
      var models = new FakeModelGateway(script, mapper);
      var index = new JavaProjectIndexer().index(workspace.root(), "banking-fixture-v2");
      var request =
          new AgentRunRequest(
              "daily-limit-e2e",
              task.requirement(),
              workspace.root(),
              index,
              new ContextRequest("daily transfer limit", 2_000, 20, 2),
              new RuntimeBudget(10, 2, 10_000, new BigDecimal("1"), Duration.ofMinutes(3)),
              "fake-replay");
      var result =
          runtime(models, mapper, tools, verification, stateRoot, "restart-0").execute(request);
      assertEquals(RuntimeStatus.WAITING_FOR_APPROVAL, result.checkpoint().status());

      approve(
          mapper, stateRoot, request.runId(), result.checkpoint().pendingAction(), planDigest, 1);
      var firstCrashJournal =
          new CrashAfterStatusJournal(
              new FileActionJournal(stateRoot.resolve("actions"), mapper), ActionStatus.SUCCEEDED);
      assertThrows(
          SimulatedCrash.class,
          () ->
              runtime(
                      models,
                      mapper,
                      tools,
                      verification,
                      stateRoot,
                      "restart-1",
                      new FileCheckpointStore(stateRoot.resolve("checkpoints"), mapper),
                      firstCrashJournal)
                  .resume(request.runId()));

      result =
          runtime(models, mapper, tools, verification, stateRoot, "restart-2")
              .resume(request.runId());
      assertEquals(RuntimeStatus.WAITING_FOR_APPROVAL, result.checkpoint().status());
      approve(
          mapper, stateRoot, request.runId(), result.checkpoint().pendingAction(), planDigest, 2);
      var secondCrashJournal =
          new CrashAfterStatusJournal(
              new FileActionJournal(stateRoot.resolve("actions"), mapper), ActionStatus.RUNNING);
      assertThrows(
          SimulatedCrash.class,
          () ->
              runtime(
                      models,
                      mapper,
                      tools,
                      verification,
                      stateRoot,
                      "restart-3",
                      new FileCheckpointStore(stateRoot.resolve("checkpoints"), mapper),
                      secondCrashJournal)
                  .resume(request.runId()));

      result =
          runtime(models, mapper, tools, verification, stateRoot, "restart-4")
              .resume(request.runId());
      assertEquals(RuntimeStatus.NEEDS_REVIEW, result.checkpoint().status());
      approve(
          mapper, stateRoot, request.runId(), result.checkpoint().pendingAction(), planDigest, 3);
      result =
          runtime(models, mapper, tools, verification, stateRoot, "restart-5")
              .resume(request.runId());
      assertEquals(RuntimeStatus.WAITING_FOR_APPROVAL, result.checkpoint().status());

      approve(
          mapper, stateRoot, request.runId(), result.checkpoint().pendingAction(), planDigest, 4);
      var crashBeforeCheckpoint =
          new CrashBeforeSaveCheckpointStore(
              new FileCheckpointStore(stateRoot.resolve("checkpoints"), mapper));
      assertThrows(
          SimulatedCrash.class,
          () ->
              runtime(
                      models,
                      mapper,
                      tools,
                      verification,
                      stateRoot,
                      "restart-6",
                      crashBeforeCheckpoint,
                      new FileActionJournal(stateRoot.resolve("actions"), mapper))
                  .resume(request.runId()));

      result =
          runtime(models, mapper, tools, verification, stateRoot, "restart-7")
              .resume(request.runId());
      assertEquals(RuntimeStatus.WAITING_FOR_APPROVAL, result.checkpoint().status());
      approve(
          mapper, stateRoot, request.runId(), result.checkpoint().pendingAction(), planDigest, 5);
      result =
          runtime(models, mapper, tools, verification, stateRoot, "restart-8")
              .resume(request.runId());

      assertEquals(RuntimeStatus.SUCCEEDED, result.checkpoint().status());
      assertEquals(RiskLevel.LOW, result.verification().risk());
      assertTrue(Files.readString(workspace.root().resolve(servicePath)).contains("synchronized"));
      assertEquals(
          4,
          new FileActionJournal(stateRoot.resolve("actions"), mapper)
              .records(request.runId())
              .size());
      assertTrue(
          new FileTraceSink(stateRoot.resolve("traces"), mapper, new SensitiveDataRedactor(2_048))
              .events(request.runId()).stream()
                  .anyMatch(event -> event.type().equals("context.compacted")));
    }
  }

  private static AgentRuntime runtime(
      FakeModelGateway models,
      ObjectMapper mapper,
      ToolExecutor tools,
      VerificationPipeline verification,
      Path stateRoot,
      String owner) {
    return runtime(
        models,
        mapper,
        tools,
        verification,
        stateRoot,
        owner,
        new FileCheckpointStore(stateRoot.resolve("checkpoints"), mapper),
        new FileActionJournal(stateRoot.resolve("actions"), mapper));
  }

  private static AgentRuntime runtime(
      FakeModelGateway models,
      ObjectMapper mapper,
      ToolExecutor tools,
      VerificationPipeline verification,
      Path stateRoot,
      String owner,
      CheckpointStore checkpoints,
      ActionJournal journal) {
    return new AgentRuntime(
        new AgentPlanner(models, mapper),
        ContextBuilder.defaults(),
        tools,
        verification,
        checkpoints,
        new FileAgentRunRequestStore(stateRoot.resolve("requests"), mapper),
        new FileApprovalStore(stateRoot.resolve("approvals.json"), mapper),
        new FileTraceSink(stateRoot.resolve("traces"), mapper, new SensitiveDataRedactor(2_048)),
        new FileReportStore(stateRoot.resolve("reports"), mapper),
        new AgentMetrics(new SimpleMeterRegistry()),
        mapper,
        Clock.systemUTC(),
        journal,
        new FileRunLeaseStore(stateRoot.resolve("leases"), mapper),
        new FileTaskMemoryStore(stateRoot.resolve("memory"), mapper, 1_000),
        new ContextCompactor(TokenEstimator.conservative()),
        owner,
        Duration.ofMinutes(1));
  }

  private static void approve(
      ObjectMapper mapper,
      Path stateRoot,
      String runId,
      AgentAction pending,
      String planDigest,
      int restart) {
    new FileApprovalStore(stateRoot.resolve("approvals.json"), mapper)
        .save(
            new RuntimeApproval(
                runId,
                ActionDigests.create(pending.tool(), pending.input()),
                planDigest,
                "fixture-reviewer",
                RuntimeApproval.Decision.APPROVED,
                ApprovalScope.ONCE,
                "restart " + restart + " reviewed",
                Instant.now().plusSeconds(300)));
  }

  private static final class CrashAfterStatusJournal implements ActionJournal {
    private final ActionJournal delegate;
    private final ActionStatus target;
    private boolean armed = true;

    private CrashAfterStatusJournal(ActionJournal delegate, ActionStatus target) {
      this.delegate = delegate;
      this.target = target;
    }

    @Override
    public java.util.Optional<ActionRecord> find(String runId, String actionId) {
      return delegate.find(runId, actionId);
    }

    @Override
    public void save(ActionRecord record) {
      delegate.save(record);
      if (armed && record.status() == target) {
        armed = false;
        throw new SimulatedCrash("crash after action " + target);
      }
    }

    @Override
    public List<ActionRecord> records(String runId) {
      return delegate.records(runId);
    }
  }

  private static final class CrashBeforeSaveCheckpointStore implements CheckpointStore {
    private final CheckpointStore delegate;
    private boolean armed = true;

    private CrashBeforeSaveCheckpointStore(CheckpointStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public void save(io.legacypilot.runtime.AgentCheckpoint checkpoint) {
      if (armed) {
        armed = false;
        throw new SimulatedCrash("crash before checkpoint save");
      }
      delegate.save(checkpoint);
    }

    @Override
    public java.util.Optional<io.legacypilot.runtime.AgentCheckpoint> load(String runId) {
      return delegate.load(runId);
    }
  }

  private static final class SimulatedCrash extends RuntimeException {
    private SimulatedCrash(String message) {
      super(message);
    }
  }

  private static AgentAction patch(
      ObjectMapper mapper, Path workspace, Path reference, String relative) throws Exception {
    var target = workspace.resolve(relative);
    var current = Files.isRegularFile(target) ? Files.readString(target) : "";
    var replacement = Files.readString(reference.resolve(relative));
    var input = mapper.createObjectNode();
    input.put("path", relative);
    input.put("expectedSha256", sha256(current));
    input.put("replacement", replacement);
    return new AgentAction("TOOL", "apply_patch", input, "apply reviewed change");
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}

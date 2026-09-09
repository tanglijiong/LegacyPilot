package io.legacypilot.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.legacypilot.analysis.java.ProjectIndex;
import io.legacypilot.analysis.java.SourcePosition;
import io.legacypilot.analysis.java.SourceRange;
import io.legacypilot.analysis.java.SourceSymbol;
import io.legacypilot.analysis.java.SymbolKind;
import io.legacypilot.context.ContextBuilder;
import io.legacypilot.context.ContextCompactor;
import io.legacypilot.context.ContextRequest;
import io.legacypilot.context.InMemoryTaskMemoryStore;
import io.legacypilot.context.TokenEstimator;
import io.legacypilot.domain.task.TaskMode;
import io.legacypilot.model.FakeModelGateway;
import io.legacypilot.observability.AgentMetrics;
import io.legacypilot.observability.InMemoryTraceSink;
import io.legacypilot.observability.ReportStore;
import io.legacypilot.observability.RunReport;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
  void renewsLeaseWhileVerificationBlocksLongerThanItsTtl() {
    var models =
        new FakeModelGateway(
            List.of(plan(), new AgentAction("VERIFY", "", null, "verify")), MAPPER);
    var tools =
        new ToolExecutor(
            new ToolRegistry(List.of(readTool())), new DefaultExecutionPolicy(), MAPPER);
    var verification =
        new VerificationPipeline(
            List.of(
                context -> {
                  try {
                    Thread.sleep(900);
                  } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                  }
                  return new VerificationEvidence(
                      "slow-tests",
                      VerificationStatus.PASSED,
                      true,
                      false,
                      "slow-test",
                      0,
                      "slow verification passed",
                      "",
                      Duration.ofMillis(900));
                }));
    var report = new AtomicReference<RunReport>();
    ReportStore reports =
        new ReportStore() {
          @Override
          public void save(RunReport value) {
            report.set(value);
          }

          @Override
          public Optional<RunReport> load(String runId) {
            return Optional.ofNullable(report.get()).filter(value -> value.runId().equals(runId));
          }
        };
    var runtime =
        new AgentRuntime(
            new AgentPlanner(models, MAPPER),
            ContextBuilder.defaults(),
            tools,
            verification,
            new InMemoryCheckpointStore(),
            new InMemoryAgentRunRequestStore(),
            new InMemoryApprovalStore(),
            new InMemoryTraceSink(new SensitiveDataRedactor(512)),
            reports,
            new AgentMetrics(new SimpleMeterRegistry()),
            MAPPER,
            Clock.systemUTC(),
            new InMemoryActionJournal(),
            new InMemoryRunLeaseStore(),
            new InMemoryTaskMemoryStore(100),
            new ContextCompactor(TokenEstimator.conservative()),
            "slow-verification-runtime",
            Duration.ofMillis(300));

    var result = runtime.execute(request("slow-verification-run", 5));

    assertEquals(RuntimeStatus.SUCCEEDED, result.checkpoint().status());
    assertEquals("SUCCEEDED", report.get().status());
  }

  @Test
  void honoursCancellationBeforeAnyModelOrToolAction() {
    var models =
        new FakeModelGateway(
            List.of(plan(), new AgentAction("FINISH", "", null, "complete")), MAPPER);
    var runtime = runtime(models, new InMemoryCheckpointStore(), new InMemoryApprovalStore());
    runtime.requestCancellation("cancel-run");

    var result = runtime.execute(request("cancel-run", 5));

    assertEquals(RuntimeStatus.CANCELLED, result.checkpoint().status());
    assertEquals("cancelled by user", result.checkpoint().observation());
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
  void preparedPatchTransitionsDirectlyToApplyApproval() throws Exception {
    var createInput = MAPPER.createObjectNode().put("path", "src/NewType.java");
    var models =
        new FakeModelGateway(
            List.of(
                plan(), new AgentAction("TOOL", "create_patch", createInput, "prepare new file")),
            MAPPER);
    var create =
        new StubTool(
            "create_patch",
            RiskLevel.READ_ONLY,
            input ->
                MAPPER
                    .createObjectNode()
                    .put("path", "src/NewType.java")
                    .put(
                        "expectedSha256",
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                    .put("replacement", "class NewType {}\n")
                    .put("patchMode", "FULL_FILE")
                    .put("createsFile", true));
    var apply =
        new StubTool(
            "apply_patch",
            RiskLevel.WORKSPACE_WRITE,
            input -> {
              try {
                var target = workspace.resolve(input.path("path").asText());
                Files.createDirectories(target.getParent());
                Files.writeString(target, input.path("replacement").asText());
                return input;
              } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
              }
            });
    var approvals = new InMemoryApprovalStore();
    var runtime = runtime(models, new InMemoryCheckpointStore(), approvals, create, apply);

    var paused = runtime.execute(request("prepared-patch-run", 5));

    assertEquals(RuntimeStatus.WAITING_FOR_APPROVAL, paused.checkpoint().status());
    assertEquals("apply_patch", paused.checkpoint().pendingAction().tool());
    assertEquals(
        "class NewType {}\n",
        paused.checkpoint().pendingAction().input().path("replacement").asText());
    assertTrue(!paused.checkpoint().pendingAction().input().has("patchMode"));

    var pending = paused.checkpoint().pendingAction();
    approvals.save(
        new RuntimeApproval(
            "prepared-patch-run",
            ActionDigests.create(pending.tool(), pending.input()),
            ActionDigests.create("change_plan", MAPPER.valueToTree(paused.checkpoint().plan())),
            "reviewer",
            RuntimeApproval.Decision.APPROVED,
            ApprovalScope.ONCE,
            "approved",
            Instant.now().plusSeconds(60)));

    var resumed = runtime.execute(request("prepared-patch-run", 5));

    assertEquals(RuntimeStatus.SUCCEEDED, resumed.checkpoint().status());
    assertEquals("class NewType {}\n", Files.readString(workspace.resolve("src/NewType.java")));
  }

  @Test
  void canCancelOrReplanFromAnApprovalCheckpoint() {
    var input = MAPPER.createObjectNode().put("path", "src/Main.java");
    var models =
        new FakeModelGateway(
            List.of(
                plan(),
                new AgentAction("TOOL", "write_file", input, "apply change"),
                plan(),
                new AgentAction("VERIFY", "", null, "verify replanned change")),
            MAPPER);
    var checkpoints = new InMemoryCheckpointStore();
    var runtime = runtime(models, checkpoints, new InMemoryApprovalStore());

    assertEquals(
        RuntimeStatus.WAITING_FOR_APPROVAL,
        runtime.execute(request("replan-paused-run", 8)).checkpoint().status());
    assertEquals(
        RuntimeStatus.SUCCEEDED, runtime.retry("replan-paused-run", true).checkpoint().status());

    var cancellationModels =
        new FakeModelGateway(
            List.of(plan(), new AgentAction("TOOL", "write_file", input, "apply change")), MAPPER);
    var cancellationCheckpoints = new InMemoryCheckpointStore();
    var cancellationRuntime =
        runtime(cancellationModels, cancellationCheckpoints, new InMemoryApprovalStore());
    cancellationRuntime.execute(request("cancel-paused-run", 8));
    cancellationRuntime.requestCancellation("cancel-paused-run");

    assertEquals(
        RuntimeStatus.CANCELLED,
        cancellationCheckpoints.load("cancel-paused-run").orElseThrow().status());
  }

  @Test
  void retryDoesNotRepeatAnAlreadyConfirmedExternalAction() {
    var invocations = new AtomicInteger();
    var input = MAPPER.createObjectNode().put("path", "src/Main.java");
    var write = new AgentAction("TOOL", "write_file", input, "apply once");
    var models =
        new FakeModelGateway(
            List.of(
                plan(),
                write,
                new AgentAction("VERIFY", "", null, "verify"),
                write,
                new AgentAction("VERIFY", "", null, "verify again")),
            MAPPER);
    var approvals = new InMemoryApprovalStore();
    approvals.save(
        new RuntimeApproval(
            "retry-run",
            ActionDigests.create(write.tool(), write.input()),
            ActionDigests.create("change_plan", MAPPER.valueToTree(plan())),
            "reviewer",
            RuntimeApproval.Decision.APPROVED,
            ApprovalScope.ONCE,
            "approved",
            Instant.now().plusSeconds(60)));
    var verificationCalls = new AtomicInteger();
    var verification =
        new VerificationPipeline(
            List.of(
                context ->
                    new VerificationEvidence(
                        "tests",
                        verificationCalls.incrementAndGet() == 1
                            ? VerificationStatus.FAILED
                            : VerificationStatus.PASSED,
                        true,
                        false,
                        "run_tests",
                        1,
                        "test result",
                        "",
                        Duration.ofMillis(1))));
    var trace = new InMemoryTraceSink(new SensitiveDataRedactor(512));
    var tools =
        new ToolExecutor(
            new ToolRegistry(List.of(readTool(), writeTool(invocations))),
            new DefaultExecutionPolicy(),
            MAPPER);
    var runtime =
        new AgentRuntime(
            new AgentPlanner(models, MAPPER),
            ContextBuilder.defaults(),
            tools,
            verification,
            new InMemoryCheckpointStore(),
            approvals,
            trace,
            new AgentMetrics(new SimpleMeterRegistry()),
            MAPPER,
            Clock.systemUTC());

    assertEquals(
        RuntimeStatus.FAILED, runtime.execute(request("retry-run", 8)).checkpoint().status());
    var retried = runtime.retry("retry-run", false);

    assertEquals(RuntimeStatus.SUCCEEDED, retried.checkpoint().status());
    assertEquals(1, invocations.get());
    assertTrue(
        trace.events("retry-run").stream()
            .anyMatch(event -> event.type().equals("action.replay.skipped")));
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

  @Test
  void defaultsLegacyRunRequestsWithoutModeToChange() throws Exception {
    var json = MAPPER.valueToTree(request("legacy-run", 5));
    ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("mode");

    var restored = MAPPER.treeToValue(json, AgentRunRequest.class);

    assertEquals(TaskMode.CHANGE, restored.mode());
  }

  @Test
  void completesAnalyzeTaskWithGroundedArtifact() throws Exception {
    var source = sourceIndex();
    var models =
        new FakeModelGateway(
            List.of(plan(), submittedResult(TaskMode.ANALYZE, "TransferService enforces the rule")),
            MAPPER);
    var runtime =
        runtime(
            models,
            new InMemoryCheckpointStore(),
            new InMemoryApprovalStore(),
            writeTool(new AtomicInteger()),
            gitDiffTool());

    var result = runtime.execute(request("analyze-run", 5, TaskMode.ANALYZE, source));

    assertEquals(RuntimeStatus.SUCCEEDED, result.checkpoint().status());
    assertEquals("ANALYZE", result.report().taskMode());
    assertEquals("ANALYZE", result.report().artifact().taskMode());
    assertEquals("0", result.report().groundingMetrics().get("unsupportedClaimCount"));
  }

  @Test
  void bindsMissingModelSymbolToAnIndexedTypeNamedInTheClaim() throws Exception {
    var input = MAPPER.createObjectNode().put("summary", "grounded");
    var sections = input.putObject("sections");
    sections
        .putArray("actors")
        .addObject()
        .put("claim", "TransferService participates in the transfer flow.")
        .put("symbol", "");
    input.putArray("unknowns");
    var request = request("symbol-fallback", 5, TaskMode.EXPLAIN, sourceIndex());

    var artifact = new ReadOnlyArtifactVerifier(MAPPER).parse(request, input);

    assertEquals(1, artifact.evidence().size());
    assertEquals("TransferService", artifact.evidence().getFirst().symbol());
    assertEquals("src/Main.java", artifact.evidence().getFirst().path());
    assertEquals("class TransferService {}", artifact.evidence().getFirst().excerpt());
    assertEquals("HEAD", artifact.evidence().getFirst().revision());
  }

  @Test
  void retriesWhenSubmittedEvidenceExcerptIsNotInTheSource() throws Exception {
    var invalidInput =
        submittedResult(TaskMode.ANALYZE, "TransferService enforces the rule").input();
    ((com.fasterxml.jackson.databind.node.ObjectNode) invalidInput.path("evidence").path(0))
        .put("excerpt", "invented source text");
    var invalid = new AgentAction("SUBMIT_RESULT", "", invalidInput, "submit invalid result");
    var models =
        new FakeModelGateway(
            List.of(
                plan(),
                invalid,
                submittedResult(TaskMode.ANALYZE, "TransferService enforces the rule")),
            MAPPER);
    var runtime =
        runtime(models, new InMemoryCheckpointStore(), new InMemoryApprovalStore(), gitDiffTool());

    var result = runtime.execute(request("evidence-retry-run", 6, TaskMode.ANALYZE, sourceIndex()));

    assertEquals(RuntimeStatus.SUCCEEDED, result.checkpoint().status());
    assertEquals(1, result.checkpoint().retries());
  }

  @Test
  void blocksReadOnlyResultWhenWorkspaceContainsUntrackedChanges() throws Exception {
    var models =
        new FakeModelGateway(
            List.of(plan(), submittedResult(TaskMode.EXPLAIN, "TransferService enforces the rule")),
            MAPPER);
    var dirtyDiff =
        new StubTool(
            "git_diff",
            RiskLevel.READ_ONLY,
            input ->
                MAPPER
                    .createObjectNode()
                    .put("diff", "")
                    .put("numstat", "")
                    .put("status", "?? generated.txt"));
    var runtime =
        runtime(models, new InMemoryCheckpointStore(), new InMemoryApprovalStore(), dirtyDiff);

    var result = runtime.execute(request("dirty-read-run", 5, TaskMode.EXPLAIN, sourceIndex()));

    assertEquals(RuntimeStatus.FAILED, result.checkpoint().status());
    assertEquals("BLOCKED", result.report().risk());
    assertEquals("1", result.report().groundingMetrics().get("writeViolationCount"));
  }

  @Test
  void readOnlyModeRejectsWriteBeforeApprovalAndStillAllowsGroundedResult() throws Exception {
    var invocations = new AtomicInteger();
    var write =
        new AgentAction(
            "TOOL", "write_file", MAPPER.createObjectNode().put("path", "src/Main.java"), "write");
    var models =
        new FakeModelGateway(
            List.of(
                plan(),
                write,
                submittedResult(TaskMode.EXPLAIN, "TransferService enforces the rule")),
            MAPPER);
    var approvals = new InMemoryApprovalStore();
    var writeDigest = ActionDigests.create(write.tool(), write.input());
    var planDigest = ActionDigests.create("change_plan", MAPPER.valueToTree(plan()));
    approvals.save(
        new RuntimeApproval(
            "explain-run",
            writeDigest,
            planDigest,
            "reviewer",
            RuntimeApproval.Decision.APPROVED,
            ApprovalScope.ONCE,
            "should not override task mode",
            Instant.now().plusSeconds(60)));
    var runtime =
        runtime(
            models,
            new InMemoryCheckpointStore(),
            approvals,
            writeTool(invocations),
            gitDiffTool());

    var result = runtime.execute(request("explain-run", 6, TaskMode.EXPLAIN, sourceIndex()));

    assertEquals(RuntimeStatus.SUCCEEDED, result.checkpoint().status());
    assertEquals(0, invocations.get());
    assertTrue(
        approvals
            .consumeMatching("explain-run", writeDigest, planDigest, Instant.now())
            .isPresent());
    assertTrue(
        result.report().trace().stream()
            .anyMatch(event -> event.type().equals("task-mode.denied")));
  }

  private AgentRuntime runtime(
      FakeModelGateway models,
      CheckpointStore checkpoints,
      ApprovalStore approvals,
      AgentTool... extra) {
    var tools = new java.util.ArrayList<AgentTool>();
    tools.add(readTool());
    if (extra.length == 0) {
      tools.add(writeTool(new AtomicInteger()));
    } else {
      tools.addAll(List.of(extra));
    }
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

  private AgentRunRequest request(
      String runId, int steps, TaskMode mode, ProjectIndex projectIndex) {
    return new AgentRunRequest(
        runId,
        "explain the transfer rule",
        mode,
        workspace,
        projectIndex,
        new ContextRequest("transfer rule", 500, 10, 1),
        new RuntimeBudget(steps, 2, 1_000, new BigDecimal("1"), Duration.ofMinutes(1)),
        "fake");
  }

  private ProjectIndex sourceIndex() throws Exception {
    Files.createDirectories(workspace.resolve("src"));
    Files.writeString(workspace.resolve("src/Main.java"), "class TransferService {}\n");
    var symbol =
        new SourceSymbol(
            "type:TransferService",
            SymbolKind.CLASS,
            "TransferService",
            "TransferService",
            "TransferService",
            "src/Main.java",
            new SourceRange(new SourcePosition(1, 1), new SourcePosition(1, 24)),
            Set.of(),
            Set.of(),
            Set.of(),
            "",
            "class TransferService {}",
            false);
    return new ProjectIndex(
        ProjectIndex.CURRENT_SCHEMA_VERSION, "HEAD", List.of(symbol), List.of(), List.of());
  }

  private static AgentAction submittedResult(TaskMode mode, String claim) {
    var input = MAPPER.createObjectNode();
    input.put("summary", "The transfer flow is source-grounded.");
    var sections = input.putObject("sections");
    var names =
        mode == TaskMode.ANALYZE
            ? List.of(
                "entryPoints",
                "callPaths",
                "affectedComponents",
                "businessRules",
                "dataOperations",
                "risks",
                "recommendedTests")
            : List.of(
                "actors",
                "triggers",
                "mainFlow",
                "decisionRules",
                "stateChanges",
                "dataChanges",
                "exceptionFlows",
                "glossary");
    names.forEach(name -> sections.putArray(name).add(claim));
    input
        .putArray("evidence")
        .addObject()
        .put("path", "src/Main.java")
        .put("symbol", "TransferService")
        .put("startLine", 1)
        .put("endLine", 1)
        .put("excerpt", "class TransferService {}")
        .put("claim", claim)
        .put("revision", "HEAD");
    input.putArray("unknowns");
    return new AgentAction("SUBMIT_RESULT", "", input, "submit grounded result");
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

  private static AgentTool gitDiffTool() {
    return new StubTool(
        "git_diff",
        RiskLevel.READ_ONLY,
        input -> MAPPER.createObjectNode().put("diff", "").put("numstat", "").put("status", ""));
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

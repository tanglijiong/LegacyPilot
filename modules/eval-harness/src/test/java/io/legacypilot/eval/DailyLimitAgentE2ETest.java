package io.legacypilot.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.analysis.java.JavaProjectIndexer;
import io.legacypilot.context.ContextBuilder;
import io.legacypilot.context.ContextRequest;
import io.legacypilot.model.FakeModelGateway;
import io.legacypilot.observability.AgentMetrics;
import io.legacypilot.observability.InMemoryTraceSink;
import io.legacypilot.observability.SensitiveDataRedactor;
import io.legacypilot.runtime.AgentAction;
import io.legacypilot.runtime.AgentPlanner;
import io.legacypilot.runtime.AgentRunRequest;
import io.legacypilot.runtime.AgentRuntime;
import io.legacypilot.runtime.ApprovalScope;
import io.legacypilot.runtime.ChangePlan;
import io.legacypilot.runtime.InMemoryApprovalStore;
import io.legacypilot.runtime.InMemoryCheckpointStore;
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
      var approvals = new InMemoryApprovalStore();
      var planDigest = ActionDigests.create("change_plan", mapper.valueToTree(plan));
      approvals.save(
          new RuntimeApproval(
              "daily-limit-e2e",
              "plan-scope",
              planDigest,
              "fixture-reviewer",
              RuntimeApproval.Decision.APPROVED,
              ApprovalScope.MATCHING_PLAN,
              "deterministic fixture",
              Instant.now().plusSeconds(300)));
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
      var runtime =
          new AgentRuntime(
              new AgentPlanner(new FakeModelGateway(script, mapper), mapper),
              ContextBuilder.defaults(),
              tools,
              verification,
              new InMemoryCheckpointStore(),
              approvals,
              new InMemoryTraceSink(new SensitiveDataRedactor(2_048)),
              new AgentMetrics(new SimpleMeterRegistry()),
              mapper,
              Clock.systemUTC());
      var index = new JavaProjectIndexer().index(workspace.root(), "banking-fixture-v2");
      var result =
          runtime.execute(
              new AgentRunRequest(
                  "daily-limit-e2e",
                  task.requirement(),
                  workspace.root(),
                  index,
                  new ContextRequest("daily transfer limit", 2_000, 20, 2),
                  new RuntimeBudget(10, 2, 10_000, new BigDecimal("1"), Duration.ofMinutes(3)),
                  "fake-replay"));

      assertEquals(RuntimeStatus.SUCCEEDED, result.checkpoint().status());
      assertEquals(RiskLevel.LOW, result.verification().risk());
      assertTrue(Files.readString(workspace.root().resolve(servicePath)).contains("synchronized"));
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

package io.legacypilot.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.tool.spi.AgentTool;
import io.legacypilot.tool.spi.DefaultExecutionPolicy;
import io.legacypilot.tool.spi.Idempotency;
import io.legacypilot.tool.spi.JsonSchemas;
import io.legacypilot.tool.spi.ToolContext;
import io.legacypilot.tool.spi.ToolDescriptor;
import io.legacypilot.tool.spi.ToolExecutor;
import io.legacypilot.tool.spi.ToolRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerificationPipelineTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  @TempDir Path workspace;

  @Test
  void parsesBuildEvidenceAndRequiredChecks() {
    var output = "Tests run: 4, Failures: 0, Errors: 0, Skipped: 1\nBUILD SUCCESS\ncoverage 91.5%";
    var pipeline =
        new VerificationPipeline(
            List.of(
                new ToolVerificationCheck(
                    "tests", "build_tool", MAPPER.createObjectNode(), true, true)));

    var result = pipeline.verify(context(tool("build_tool", output)));

    assertTrue(result.successful());
    assertEquals(RiskLevel.LOW, result.risk());
    assertTrue(result.evidence().getFirst().summary().contains("tests=4"));
    assertTrue(result.evidence().getFirst().summary().contains("coverage=91.5%"));
  }

  @Test
  void capturesInternalFailuresAndRepairFeedback() {
    VerificationCheck broken =
        context -> {
          throw new IllegalStateException("sensitive");
        };
    var result = new VerificationPipeline(List.of(broken)).verify(context(tool("unused", "ok")));

    assertFalse(result.successful());
    assertFalse(result.repairable());
    assertEquals(RiskLevel.HIGH, result.risk());
    assertTrue(result.repairFeedback().contains("failed internally"));
  }

  @Test
  void validatesWorkspaceIntegrity() throws Exception {
    var pipeline = new VerificationPipeline(List.of(new WorkspaceIntegrityCheck()));
    assertTrue(pipeline.verify(context(tool("unused", "ok"))).successful());

    var outside = java.nio.file.Files.createTempFile("legacy-pilot-outside", ".txt");
    java.nio.file.Files.createSymbolicLink(workspace.resolve("escape"), outside);
    var result = pipeline.verify(context(tool("unused", "ok")));
    assertFalse(result.successful());
    assertEquals(VerificationStatus.BLOCKED, result.evidence().getFirst().status());
  }

  @Test
  void enforcesDiffThresholdProtectedPathsAndTruncation() {
    var accepted = diffTool("1\t2\tsrc/Main.java\n", false);
    var check = new DiffPolicyCheck(5, List.of(".github/**"));
    assertEquals(VerificationStatus.PASSED, check.verify(context(accepted)).status());

    var tooLarge = diffTool("9\t2\tsrc/Main.java\n", false);
    assertEquals(VerificationStatus.BLOCKED, check.verify(context(tooLarge)).status());
    var protectedFile = diffTool("1\t1\t.github/workflows/ci.yml\n", false);
    assertEquals(VerificationStatus.BLOCKED, check.verify(context(protectedFile)).status());
    assertEquals(VerificationStatus.BLOCKED, check.verify(context(diffTool("", true))).status());
  }

  private VerificationContext context(AgentTool tool) {
    var executor =
        new ToolExecutor(new ToolRegistry(List.of(tool)), new DefaultExecutionPolicy(), MAPPER);
    return new VerificationContext(
        workspace, new ToolContext("run-1", workspace, Set.of(), true), executor);
  }

  private static AgentTool tool(String name, String output) {
    return new StubTool(
        name, input -> MAPPER.createObjectNode().put("exitCode", 0).put("output", output));
  }

  private static AgentTool diffTool(String numstat, boolean truncated) {
    return new StubTool(
        "git_diff",
        input ->
            MAPPER
                .createObjectNode()
                .put("diff", "")
                .put("numstat", numstat)
                .put("truncated", truncated));
  }

  private record StubTool(String name, Operation operation) implements AgentTool {
    @Override
    public ToolDescriptor descriptor() {
      return new ToolDescriptor(
          name,
          "test tool",
          JsonSchemas.parse("{\"type\":\"object\"}"),
          JsonSchemas.parse("{\"type\":\"object\"}"),
          io.legacypilot.tool.spi.RiskLevel.READ_ONLY,
          Idempotency.IDEMPOTENT,
          Duration.ofSeconds(1),
          1024,
          8192,
          Set.of());
    }

    @Override
    public JsonNode execute(ToolContext context, JsonNode input) {
      return operation.apply(input);
    }
  }

  @FunctionalInterface
  private interface Operation {
    JsonNode apply(JsonNode input);
  }
}

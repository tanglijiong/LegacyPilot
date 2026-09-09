package io.legacypilot.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.analysis.java.ProjectIndex;
import io.legacypilot.context.ContextBuildResult;
import io.legacypilot.context.ContextRequest;
import io.legacypilot.domain.task.TaskMode;
import io.legacypilot.model.ModelGateway;
import io.legacypilot.model.ModelRequest;
import io.legacypilot.model.ModelResult;
import io.legacypilot.model.ModelUsage;
import io.legacypilot.observability.TaskArtifact;
import io.legacypilot.tool.spi.Idempotency;
import io.legacypilot.tool.spi.JsonSchemas;
import io.legacypilot.tool.spi.RiskLevel;
import io.legacypilot.tool.spi.ToolDescriptor;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentPlannerTest {

  @Test
  void requiresSimplifiedChineseForAChineseQuestion() {
    var captured = new AtomicReference<ModelRequest>();
    var plan = new ChangePlan(1, List.of("检查入口"), List.of(), "LOW", "只读分析");
    ModelGateway gateway =
        new ModelGateway() {
          @Override
          public <T> ModelResult<T> generate(ModelRequest request, Class<T> responseType) {
            captured.set(request);
            return new ModelResult<>(
                responseType.cast(plan),
                new ModelUsage(1, 1, BigDecimal.ZERO, "test-model"),
                Duration.ofMillis(1),
                0);
          }
        };
    var request =
        new AgentRunRequest(
            "run-chinese",
            "请解释转账保存流程",
            TaskMode.EXPLAIN,
            Path.of("."),
            new ProjectIndex(
                ProjectIndex.CURRENT_SCHEMA_VERSION, "revision", List.of(), List.of(), List.of()),
            new ContextRequest("转账", 100, 10, 1),
            new RuntimeBudget(5, 1, 1_000, BigDecimal.ONE, Duration.ofMinutes(1)),
            "test-model");

    new AgentPlanner(gateway, new ObjectMapper())
        .plan(request, new ContextBuildResult(List.of(), List.of(), 0, 100));

    assertTrue(captured.get().systemPrompt().contains("Simplified Chinese"));
    assertTrue(captured.get().systemPrompt().contains("summaries"));
  }

  @Test
  void rejectsEnglishOnlyArtifactsForAChineseQuestion() {
    var request = request("请分析转账限额");
    var english =
        new TaskArtifact(
            TaskArtifact.CURRENT_SCHEMA_VERSION,
            "ANALYZE",
            "The limit is enforced in the service layer.",
            Map.of("businessRules", List.of("The service validates the daily total.")),
            List.of(),
            List.of("Concurrency behavior is unknown."));
    var chinese =
        new TaskArtifact(
            TaskArtifact.CURRENT_SCHEMA_VERSION,
            "ANALYZE",
            "转账限额在服务层执行。",
            Map.of("businessRules", List.of("服务会校验每日累计金额。")),
            List.of(),
            List.of("并发行为仍需确认。"));

    assertTrue(!ResponseLanguage.matches(request, english));
    assertTrue(ResponseLanguage.matches(request, chinese));
  }

  @Test
  void constrainsChangeActionsToRegisteredToolNamesAndDescribesTheirInputs() {
    var captured = new AtomicReference<ModelRequest>();
    ModelGateway gateway =
        new ModelGateway() {
          @Override
          public <T> ModelResult<T> generate(ModelRequest request, Class<T> responseType) {
            captured.set(request);
            return new ModelResult<>(
                responseType.cast(new AgentAction("VERIFY", "", null, "验证变更")),
                ModelUsage.NONE,
                Duration.ZERO,
                0);
          }
        };
    var descriptor =
        new ToolDescriptor(
            "read_file",
            "Read a source file.",
            JsonSchemas.parse(
                "{\"type\":\"object\",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"}}}"),
            JsonSchemas.parse("{\"type\":\"object\"}"),
            RiskLevel.READ_ONLY,
            Idempotency.IDEMPOTENT,
            Duration.ofSeconds(1),
            1024,
            1024,
            java.util.Set.of());
    var internalVerificationTool =
        new ToolDescriptor(
            "static_analysis",
            "Run static analysis.",
            JsonSchemas.parse("{\"type\":\"object\"}"),
            JsonSchemas.parse("{\"type\":\"object\"}"),
            RiskLevel.COMMAND_EXECUTION,
            Idempotency.CONDITIONAL,
            Duration.ofSeconds(1),
            1024,
            1024,
            java.util.Set.of());
    var createPatchTool =
        new ToolDescriptor(
            "create_patch",
            "Prepare a source patch.",
            JsonSchemas.parse(
                "{\"type\":\"object\",\"required\":[\"path\",\"replacement\"],\"properties\":{\"path\":{\"type\":\"string\"},\"replacement\":{\"type\":\"string\"}}}"),
            JsonSchemas.parse("{\"type\":\"object\"}"),
            RiskLevel.READ_ONLY,
            Idempotency.IDEMPOTENT,
            Duration.ofSeconds(1),
            1024,
            1024,
            java.util.Set.of());
    var request =
        new AgentRunRequest(
            "run-change",
            "优化性能",
            TaskMode.CHANGE,
            Path.of("."),
            new ProjectIndex(
                ProjectIndex.CURRENT_SCHEMA_VERSION, "revision", List.of(), List.of(), List.of()),
            new ContextRequest("性能", 100, 10, 1),
            new RuntimeBudget(5, 1, 1_000, BigDecimal.ONE, Duration.ofMinutes(1)),
            "test-model");

    new AgentPlanner(
            gateway,
            new ObjectMapper(),
            List.of(descriptor, createPatchTool, internalVerificationTool))
        .next(
            request,
            new ChangePlan(1, List.of("读取目标"), List.of("Example.java"), "LOW", "优化"),
            "",
            1,
            new ContextBuildResult(List.of(), List.of(), 0, 100));

    assertTrue(captured.get().outputSchema().toString().contains("read_file"));
    assertTrue(captured.get().outputSchema().toString().contains("create_patch"));
    assertTrue(!captured.get().outputSchema().toString().contains("static_analysis"));
    assertTrue(captured.get().userPrompt().contains("Read a source file."));
    assertTrue(
        captured
            .get()
            .userPrompt()
            .contains("A Java class, method, file, command, or plan step is never a tool name"));
    assertTrue(captured.get().userPrompt().contains("target file does not exist"));
    assertTrue(captured.get().userPrompt().contains("call create_patch directly"));
  }

  @Test
  void onlyAllowsApplyAfterAPatchHasBeenPrepared() {
    var captured = new AtomicReference<ModelRequest>();
    ModelGateway gateway =
        new ModelGateway() {
          @Override
          public <T> ModelResult<T> generate(ModelRequest request, Class<T> responseType) {
            captured.set(request);
            return new ModelResult<>(
                responseType.cast(new AgentAction("VERIFY", "", null, "验证变更")),
                ModelUsage.NONE,
                Duration.ZERO,
                0);
          }
        };
    var create = descriptor("create_patch", RiskLevel.READ_ONLY);
    var apply = descriptor("apply_patch", RiskLevel.WORKSPACE_WRITE);

    new AgentPlanner(gateway, new ObjectMapper(), List.of(create, apply))
        .next(
            changeRequest(),
            new ChangePlan(1, List.of("创建文件"), List.of("Example.java"), "LOW", "新增"),
            "{\"expectedSha256\":\"abc\",\"replacement\":\"source\"}",
            2,
            new ContextBuildResult(List.of(), List.of(), 0, 100));

    var schema = captured.get().outputSchema().toString();
    assertTrue(schema.contains("apply_patch"));
    assertTrue(!schema.contains("create_patch"));
  }

  private static ToolDescriptor descriptor(String name, RiskLevel risk) {
    return new ToolDescriptor(
        name,
        name,
        JsonSchemas.parse("{\"type\":\"object\"}"),
        JsonSchemas.parse("{\"type\":\"object\"}"),
        risk,
        Idempotency.IDEMPOTENT,
        Duration.ofSeconds(1),
        1024,
        1024,
        java.util.Set.of());
  }

  private static AgentRunRequest changeRequest() {
    return new AgentRunRequest(
        "run-change-state",
        "增加功能",
        TaskMode.CHANGE,
        Path.of("."),
        new ProjectIndex(
            ProjectIndex.CURRENT_SCHEMA_VERSION, "revision", List.of(), List.of(), List.of()),
        new ContextRequest("功能", 100, 10, 1),
        new RuntimeBudget(5, 1, 1_000, BigDecimal.ONE, Duration.ofMinutes(1)),
        "test-model");
  }

  private static AgentRunRequest request(String requirement) {
    return new AgentRunRequest(
        "run-language",
        requirement,
        TaskMode.ANALYZE,
        Path.of("."),
        new ProjectIndex(
            ProjectIndex.CURRENT_SCHEMA_VERSION, "revision", List.of(), List.of(), List.of()),
        new ContextRequest(requirement, 100, 10, 1),
        new RuntimeBudget(5, 1, 1_000, BigDecimal.ONE, Duration.ofMinutes(1)),
        "test-model");
  }
}

package io.legacypilot.tool.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolExecutorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void executesRedactsAndDefensivelyCopiesResults() throws Exception {
    var tool =
        tool(
            "read_tool",
            RiskLevel.READ_ONLY,
            Duration.ofSeconds(1),
            1024,
            1024,
            input -> {
              var output = MAPPER.createObjectNode();
              output.putObject("nested").put("secret", "token");
              output.put("value", 1);
              return output;
            });
    var executor =
        new ToolExecutor(new ToolRegistry(List.of(tool)), new DefaultExecutionPolicy(), MAPPER);
    var result =
        executor.execute("read_tool", context(false, Set.of()), MAPPER.readTree("{\"value\":1}"));

    assertTrue(result.successful());
    assertEquals("[REDACTED]", result.output().at("/nested/secret").asText());
    var changed = (com.fasterxml.jackson.databind.node.ObjectNode) result.output();
    changed.put("value", 99);
    assertEquals(1, result.output().path("value").asInt());
    assertFalse(result.outputTruncated());
  }

  @Test
  void enforcesRegistrationSchemaAndInputLimits() throws Exception {
    var tool =
        tool("read_tool", RiskLevel.READ_ONLY, Duration.ofSeconds(1), 20, 1024, input -> input);
    var executor =
        new ToolExecutor(new ToolRegistry(List.of(tool)), new DefaultExecutionPolicy(), MAPPER);

    assertEquals(
        ToolErrorCode.UNREGISTERED_TOOL,
        executor
            .execute("missing", context(false, Set.of()), MAPPER.createObjectNode())
            .error()
            .code());
    assertEquals(
        ToolErrorCode.INVALID_INPUT_SCHEMA,
        executor
            .execute("read_tool", context(false, Set.of()), MAPPER.readTree("{}"))
            .error()
            .code());
    assertEquals(
        ToolErrorCode.INPUT_TOO_LARGE,
        executor
            .execute(
                "read_tool",
                context(false, Set.of()),
                MAPPER.readTree("{\"value\":\"a very long value\"}"))
            .error()
            .code());
  }

  @Test
  void bindsApprovalToExactActionDigestAndDeniesExternalIo() throws Exception {
    var write =
        tool(
            "write_tool",
            RiskLevel.WORKSPACE_WRITE,
            Duration.ofSeconds(1),
            1024,
            1024,
            input -> input);
    var external =
        tool(
            "external_tool",
            RiskLevel.EXTERNAL_IO,
            Duration.ofSeconds(1),
            1024,
            1024,
            input -> input);
    var executor =
        new ToolExecutor(
            new ToolRegistry(List.of(write, external)), new DefaultExecutionPolicy(), MAPPER);
    var firstInput = MAPPER.readTree("{\"value\":1}");
    var approval = executor.execute("write_tool", context(false, Set.of()), firstInput);

    assertEquals(ToolStatus.APPROVAL_REQUIRED, approval.status());
    assertTrue(
        executor
            .execute("write_tool", context(false, Set.of(approval.actionDigest())), firstInput)
            .successful());
    var changed =
        executor.execute(
            "write_tool",
            context(false, Set.of(approval.actionDigest())),
            MAPPER.readTree("{\"value\":2}"));
    assertEquals(ToolStatus.APPROVAL_REQUIRED, changed.status());
    assertNotEquals(approval.actionDigest(), changed.actionDigest());
    assertEquals(
        ToolStatus.POLICY_DENIED,
        executor.execute("external_tool", context(false, Set.of()), firstInput).status());
  }

  @Test
  void handlesTimeoutToolFailuresInternalErrorsInvalidOutputAndTruncation() throws Exception {
    var timeout =
        tool(
            "timeout_tool",
            RiskLevel.READ_ONLY,
            Duration.ofMillis(20),
            1024,
            1024,
            input -> {
              try {
                Thread.sleep(500);
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
              }
              return input;
            });
    var failure =
        tool(
            "failure_tool",
            RiskLevel.READ_ONLY,
            Duration.ofSeconds(1),
            1024,
            1024,
            input -> {
              throw new ToolFailureException(ToolErrorCode.PATCH_CONFLICT, "changed");
            });
    var internal =
        tool(
            "internal_tool",
            RiskLevel.READ_ONLY,
            Duration.ofSeconds(1),
            1024,
            1024,
            input -> {
              throw new IllegalStateException("secret detail");
            });
    var invalid =
        tool(
            "invalid_tool",
            RiskLevel.READ_ONLY,
            Duration.ofSeconds(1),
            1024,
            1024,
            input -> MAPPER.createArrayNode());
    var large =
        tool(
            "large_tool",
            RiskLevel.READ_ONLY,
            Duration.ofSeconds(1),
            1024,
            20,
            input -> MAPPER.createObjectNode().put("value", "abcdefghijklmnopqrstuvwxyz"));
    var executor =
        new ToolExecutor(
            new ToolRegistry(List.of(timeout, failure, internal, invalid, large)),
            new DefaultExecutionPolicy(),
            MAPPER);
    var input = MAPPER.readTree("{\"value\":1}");

    assertEquals(
        ToolStatus.TIMEOUT,
        executor.execute("timeout_tool", context(false, Set.of()), input).status());
    assertEquals(
        ToolErrorCode.PATCH_CONFLICT,
        executor.execute("failure_tool", context(false, Set.of()), input).error().code());
    assertEquals(
        "Tool execution failed internally",
        executor.execute("internal_tool", context(false, Set.of()), input).error().message());
    assertEquals(
        ToolErrorCode.INVALID_OUTPUT_SCHEMA,
        executor.execute("invalid_tool", context(false, Set.of()), input).error().code());
    assertTrue(executor.execute("large_tool", context(false, Set.of()), input).outputTruncated());
  }

  @Test
  void validatesRegistryDescriptorsAndContext() {
    var tool =
        tool("read_tool", RiskLevel.READ_ONLY, Duration.ofSeconds(1), 1024, 1024, input -> input);
    var registry = new ToolRegistry(List.of(tool));
    assertEquals(1, registry.descriptors().size());
    assertTrue(registry.find("read_tool").isPresent());
    assertThrows(IllegalArgumentException.class, () -> new ToolRegistry(List.of(tool, tool)));
    assertThrows(
        UnsupportedOperationException.class,
        () -> context(false, Set.of()).approvedActionDigests().add("x"));
  }

  private static ToolContext context(boolean commandAllowed, Set<String> approvals) {
    return new ToolContext("run-1", Path.of("."), approvals, commandAllowed);
  }

  private static AgentTool tool(
      String name,
      RiskLevel risk,
      Duration timeout,
      int maxInput,
      int maxOutput,
      ThrowingOperation operation) {
    var descriptor =
        new ToolDescriptor(
            name,
            "Test tool",
            JsonSchemas.parse(
                "{\"type\":\"object\",\"required\":[\"value\"],\"properties\":{\"value\":{}}}"),
            JsonSchemas.parse("{\"type\":\"object\"}"),
            risk,
            Idempotency.IDEMPOTENT,
            timeout,
            maxInput,
            maxOutput,
            Set.of("secret"));
    return new AgentTool() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public JsonNode execute(ToolContext context, JsonNode input) {
        return operation.apply(input);
      }
    };
  }

  @FunctionalInterface
  private interface ThrowingOperation {
    JsonNode apply(JsonNode input);
  }
}

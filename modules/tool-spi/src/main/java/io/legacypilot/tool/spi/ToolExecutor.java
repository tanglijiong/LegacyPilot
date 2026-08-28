package io.legacypilot.tool.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ToolExecutor {

  private final ToolRegistry registry;
  private final ExecutionPolicy policy;
  private final JsonSchemaValidator schemas;
  private final ObjectMapper mapper;

  public ToolExecutor(ToolRegistry registry, ExecutionPolicy policy, ObjectMapper mapper) {
    this.registry = Objects.requireNonNull(registry);
    this.policy = Objects.requireNonNull(policy);
    this.mapper = Objects.requireNonNull(mapper);
    this.schemas = new JsonSchemaValidator();
  }

  public ToolResult execute(String toolName, ToolContext context, JsonNode input) {
    var started = Instant.now();
    var tool = registry.find(toolName).orElse(null);
    if (tool == null) {
      return failure(
          ToolStatus.TOOL_ERROR,
          ToolErrorCode.UNREGISTERED_TOOL,
          "Tool is not registered: " + toolName,
          "",
          started);
    }
    var descriptor = tool.descriptor();
    var safeInput = input == null ? mapper.nullNode() : input.deepCopy();
    var digest = ActionDigests.create(toolName, safeInput);
    if (safeInput.toString().getBytes(StandardCharsets.UTF_8).length > descriptor.maxInputBytes()) {
      return failure(
          ToolStatus.SCHEMA_ERROR,
          ToolErrorCode.INPUT_TOO_LARGE,
          "Tool input exceeds its size limit",
          digest,
          started);
    }
    var inputErrors = schemas.validate(descriptor.inputSchema(), safeInput);
    if (!inputErrors.isEmpty()) {
      return failure(
          ToolStatus.SCHEMA_ERROR,
          ToolErrorCode.INVALID_INPUT_SCHEMA,
          String.join("; ", inputErrors),
          digest,
          started);
    }
    var decision = policy.evaluate(descriptor, context, safeInput);
    if (decision.effect() == PolicyDecision.Effect.DENY) {
      return failure(
          ToolStatus.POLICY_DENIED,
          ToolErrorCode.POLICY_DENIED,
          decision.reason(),
          decision.actionDigest(),
          started);
    }
    if (decision.effect() == PolicyDecision.Effect.REQUIRE_APPROVAL) {
      return failure(
          ToolStatus.APPROVAL_REQUIRED,
          ToolErrorCode.APPROVAL_REQUIRED,
          decision.reason(),
          decision.actionDigest(),
          started);
    }
    return invoke(tool, context, safeInput, decision.actionDigest(), started);
  }

  public java.util.Optional<ToolDescriptor> descriptor(String toolName) {
    return registry.find(toolName).map(AgentTool::descriptor);
  }

  private ToolResult invoke(
      AgentTool tool, ToolContext context, JsonNode input, String digest, Instant started) {
    Future<JsonNode> future = null;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      future = executor.submit(() -> tool.execute(context, input));
      var output = future.get(tool.descriptor().timeout().toMillis(), TimeUnit.MILLISECONDS);
      var outputErrors = schemas.validate(tool.descriptor().outputSchema(), output);
      if (!outputErrors.isEmpty()) {
        return failure(
            ToolStatus.SYSTEM_ERROR,
            ToolErrorCode.INVALID_OUTPUT_SCHEMA,
            "Tool returned an invalid result",
            digest,
            started);
      }
      var redacted = redact(output, tool.descriptor().sensitiveFields());
      if (redacted.toString().getBytes(StandardCharsets.UTF_8).length
          > tool.descriptor().maxOutputBytes()) {
        var summary = mapper.createObjectNode();
        summary.put("message", "Tool output exceeded its size limit");
        return new ToolResult(ToolStatus.SUCCESS, summary, null, digest, elapsed(started), true);
      }
      return new ToolResult(ToolStatus.SUCCESS, redacted, null, digest, elapsed(started), false);
    } catch (TimeoutException exception) {
      if (future != null) {
        future.cancel(true);
      }
      return failure(
          ToolStatus.TIMEOUT, ToolErrorCode.TIMEOUT, "Tool execution timed out", digest, started);
    } catch (ExecutionException exception) {
      var cause = exception.getCause();
      if (cause instanceof ToolFailureException failure) {
        return failure(
            ToolStatus.TOOL_ERROR, failure.code(), failure.getMessage(), digest, started);
      }
      return failure(
          ToolStatus.SYSTEM_ERROR,
          ToolErrorCode.INTERNAL_ERROR,
          "Tool execution failed internally",
          digest,
          started);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return failure(
          ToolStatus.SYSTEM_ERROR,
          ToolErrorCode.INTERNAL_ERROR,
          "Tool execution was interrupted",
          digest,
          started);
    }
  }

  private JsonNode redact(JsonNode value, java.util.Set<String> sensitiveFields) {
    var copy = value.deepCopy();
    redactNode(copy, sensitiveFields);
    return copy;
  }

  private void redactNode(JsonNode node, java.util.Set<String> sensitiveFields) {
    if (node instanceof ObjectNode object) {
      object
          .fieldNames()
          .forEachRemaining(
              name -> {
                if (sensitiveFields.contains(name)) {
                  object.put(name, "[REDACTED]");
                } else {
                  redactNode(object.get(name), sensitiveFields);
                }
              });
    } else if (node.isArray()) {
      node.forEach(child -> redactNode(child, sensitiveFields));
    }
  }

  private static ToolResult failure(
      ToolStatus status, ToolErrorCode code, String message, String digest, Instant started) {
    return new ToolResult(
        status, null, new ToolError(code, message), digest, elapsed(started), false);
  }

  private static Duration elapsed(Instant started) {
    return Duration.between(started, Instant.now());
  }
}

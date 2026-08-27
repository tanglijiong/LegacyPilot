package io.legacypilot.tool.spi;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

public record ToolDescriptor(
    String name,
    String description,
    JsonNode inputSchema,
    JsonNode outputSchema,
    RiskLevel risk,
    Idempotency idempotency,
    Duration timeout,
    int maxInputBytes,
    int maxOutputBytes,
    Set<String> sensitiveFields) {

  public ToolDescriptor {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(description, "description must not be null");
    Objects.requireNonNull(inputSchema, "inputSchema must not be null");
    Objects.requireNonNull(outputSchema, "outputSchema must not be null");
    Objects.requireNonNull(risk, "risk must not be null");
    Objects.requireNonNull(idempotency, "idempotency must not be null");
    Objects.requireNonNull(timeout, "timeout must not be null");
    Objects.requireNonNull(sensitiveFields, "sensitiveFields must not be null");
    if (!name.matches("[a-z][a-z0-9_.-]{1,95}")) {
      throw new IllegalArgumentException("tool name is invalid");
    }
    if (description.isBlank() || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("description and timeout must be positive values");
    }
    if (maxInputBytes < 1 || maxOutputBytes < 1) {
      throw new IllegalArgumentException("tool size limits must be positive");
    }
    inputSchema = inputSchema.deepCopy();
    outputSchema = outputSchema.deepCopy();
    sensitiveFields = Set.copyOf(sensitiveFields);
  }

  @Override
  public JsonNode inputSchema() {
    return inputSchema.deepCopy();
  }

  @Override
  public JsonNode outputSchema() {
    return outputSchema.deepCopy();
  }
}

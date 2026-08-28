package io.legacypilot.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public record ModelRequest(
    String systemPrompt,
    String userPrompt,
    JsonNode outputSchema,
    String model,
    double temperature,
    Duration timeout,
    Map<String, String> metadata) {

  public ModelRequest {
    Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
    Objects.requireNonNull(userPrompt, "userPrompt must not be null");
    Objects.requireNonNull(outputSchema, "outputSchema must not be null");
    Objects.requireNonNull(model, "model must not be null");
    Objects.requireNonNull(timeout, "timeout must not be null");
    Objects.requireNonNull(metadata, "metadata must not be null");
    if (userPrompt.isBlank()
        || model.isBlank()
        || temperature < 0
        || temperature > 2
        || timeout.isZero()
        || timeout.isNegative()) {
      throw new IllegalArgumentException("model request is invalid");
    }
    outputSchema = outputSchema.deepCopy();
    metadata = Map.copyOf(metadata);
  }

  @Override
  public JsonNode outputSchema() {
    return outputSchema.deepCopy();
  }

  public ModelRequest withCorrection(String correction) {
    return new ModelRequest(
        systemPrompt,
        userPrompt + "\n\nCorrect the previous response: " + correction,
        outputSchema,
        model,
        temperature,
        timeout,
        metadata);
  }

  public ModelRequest withModel(String selectedModel) {
    return new ModelRequest(
        systemPrompt, userPrompt, outputSchema, selectedModel, temperature, timeout, metadata);
  }
}

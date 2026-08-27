package io.legacypilot.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.tool.spi.JsonSchemaValidator;
import java.time.Duration;
import java.util.Objects;

public final class StructuredModelGateway implements ModelGateway {

  private final RawModelClient client;
  private final ObjectMapper mapper;
  private final JsonSchemaValidator schemas = new JsonSchemaValidator();
  private final int maximumCorrections;

  public StructuredModelGateway(
      RawModelClient client, ObjectMapper mapper, int maximumCorrections) {
    this.client = Objects.requireNonNull(client);
    this.mapper = Objects.requireNonNull(mapper);
    if (maximumCorrections < 0 || maximumCorrections > 3) {
      throw new IllegalArgumentException("maximum corrections must be between zero and three");
    }
    this.maximumCorrections = maximumCorrections;
  }

  @Override
  public <T> ModelResult<T> generate(ModelRequest request, Class<T> responseType) {
    var current = request;
    var usage = ModelUsage.NONE;
    var duration = Duration.ZERO;
    for (int correction = 0; correction <= maximumCorrections; correction++) {
      var response = client.complete(current);
      usage = usage.plus(response.usage());
      duration = duration.plus(response.duration());
      try {
        var json = mapper.readTree(stripFence(response.content()));
        var errors = schemas.validate(request.outputSchema(), json);
        if (!errors.isEmpty()) {
          throw new SchemaMismatchException(String.join("; ", errors));
        }
        return new ModelResult<>(
            mapper.treeToValue(json, responseType), usage, duration, correction);
      } catch (JsonProcessingException exception) {
        if (correction == maximumCorrections) {
          throw new ModelException(
              ModelErrorType.INVALID_RESPONSE,
              "Model response did not match the required schema",
              false,
              exception);
        }
        current = current.withCorrection("return only valid JSON matching the supplied schema");
      }
    }
    throw new IllegalStateException("unreachable model correction state");
  }

  private static String stripFence(String value) {
    var stripped = value.strip();
    if (stripped.startsWith("```json") && stripped.endsWith("```")) {
      return stripped.substring(7, stripped.length() - 3).strip();
    }
    return stripped;
  }

  private static final class SchemaMismatchException extends JsonProcessingException {
    private SchemaMismatchException(String message) {
      super(message);
    }
  }
}

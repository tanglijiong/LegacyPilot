package io.legacypilot.tool.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JsonSchemas {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private JsonSchemas() {}

  public static JsonNode parse(String schema) {
    try {
      return MAPPER.readTree(schema);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Invalid JSON schema", exception);
    }
  }
}

package io.legacypilot.tool.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JsonSchemaValidatorTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final JsonSchemaValidator validator = new JsonSchemaValidator();

  @Test
  void validatesSupportedSchemaConstraints() throws Exception {
    var schema =
        JsonSchemas.parse(
            """
        {"type":"object","required":["name","items"],"additionalProperties":false,
         "properties":{"name":{"type":"string","maxLength":3,"pattern":"[a-z]+","enum":["ok"]},
         "items":{"type":"array","maxItems":1,"items":{"type":"integer"}},
         "enabled":{"type":"boolean"},"amount":{"type":"number"},"empty":{"type":"null"}}}
        """);
    assertTrue(
        validator
            .validate(
                schema,
                mapper.readTree(
                    "{\"name\":\"ok\",\"items\":[1],\"enabled\":true,\"amount\":1.2,\"empty\":null}"))
            .isEmpty());
    var errors =
        validator.validate(
            schema, mapper.readTree("{\"name\":\"TOOLONG\",\"items\":[\"bad\",2],\"extra\":1}"));
    assertTrue(errors.size() >= 5);
    assertFalse(
        validator
            .validate(JsonSchemas.parse("{\"type\":\"unsupported\"}"), mapper.nullNode())
            .isEmpty());
  }

  @Test
  void descriptorRejectsUnsafeConfigurationAndCopiesSchemas() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolDescriptor(
                "X",
                "bad",
                mapper.createObjectNode(),
                mapper.createObjectNode(),
                RiskLevel.READ_ONLY,
                Idempotency.IDEMPOTENT,
                Duration.ZERO,
                0,
                0,
                Set.of()));
    var descriptor =
        new ToolDescriptor(
            "safe_tool",
            "safe",
            mapper.createObjectNode(),
            mapper.createObjectNode(),
            RiskLevel.READ_ONLY,
            Idempotency.IDEMPOTENT,
            Duration.ofSeconds(1),
            1,
            1,
            Set.of());
    ((com.fasterxml.jackson.databind.node.ObjectNode) descriptor.inputSchema())
        .put("changed", true);
    assertEquals(0, descriptor.inputSchema().size());
  }
}

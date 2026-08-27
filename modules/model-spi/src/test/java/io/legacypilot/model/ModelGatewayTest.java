package io.legacypilot.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.tool.spi.JsonSchemas;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelGatewayTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void correctsInvalidStructuredOutputAndAggregatesUsage() {
    var responses =
        new ArrayDeque<>(
            List.of(
                response("not json", 2, 1), response("```json\n{\"name\":\"Ada\"}\n```", 3, 2)));
    var gateway = new StructuredModelGateway(request -> responses.removeFirst(), MAPPER, 1);

    var result = gateway.generate(request(), Person.class);

    assertEquals("Ada", result.value().name());
    assertEquals(1, result.formatCorrections());
    assertEquals(8, result.usage().totalTokens());
    assertEquals(Duration.ofMillis(4), result.duration());
  }

  @Test
  void rejectsInvalidOutputWithoutLeakingIt() {
    var gateway =
        new StructuredModelGateway(request -> response("secret-invalid-output", 1, 1), MAPPER, 0);

    var error = assertThrows(ModelException.class, () -> gateway.generate(request(), Person.class));

    assertEquals(ModelErrorType.INVALID_RESPONSE, error.type());
    assertTrue(!error.getMessage().contains("secret"));
  }

  @Test
  void supportsFakeReplayCostsAndValidation() {
    var fake = new FakeModelGateway(List.of(new Person("Grace")), MAPPER);
    assertEquals("Grace", fake.generate(request(), Person.class).value().name());
    assertEquals(0, fake.remaining());
    assertThrows(ModelException.class, () -> fake.generate(request(), Person.class));

    var replay =
        new ReplayModelGateway(
            Map.of(ReplayModelGateway.key(request()), new Person("Lin")), MAPPER);
    assertEquals("Lin", replay.generate(request(), Person.class).value().name());
    assertThrows(
        ModelException.class,
        () -> replay.generate(request().withCorrection("different"), Person.class));

    var costs =
        new ModelCostTable(
            Map.of("model", new ModelCostTable.Price(new BigDecimal("2"), new BigDecimal("8"))));
    assertEquals(
        0, new BigDecimal("0.000020").compareTo(costs.price("model", 2, 2).estimatedCostUsd()));
    assertEquals(BigDecimal.ZERO, costs.price("unknown", 2, 2).estimatedCostUsd());
    assertThrows(
        IllegalArgumentException.class, () -> new StructuredModelGateway(r -> null, MAPPER, 4));
  }

  private static ModelRequest request() {
    return new ModelRequest(
        "system",
        "user",
        JsonSchemas.parse(
            """
            {"type":"object","required":["name"],"additionalProperties":false,
             "properties":{"name":{"type":"string"}}}
            """),
        "model",
        0,
        Duration.ofSeconds(1),
        Map.of());
  }

  private static RawModelResponse response(String content, int input, int output) {
    return new RawModelResponse(
        content, new ModelUsage(input, output, BigDecimal.ZERO, "model"), Duration.ofMillis(2));
  }

  record Person(String name) {}
}

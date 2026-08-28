package io.legacypilot.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.tool.spi.JsonSchemas;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RoutingModelGatewayTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneOffset.UTC);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void fallsBackOnlyForTransientFailuresAndRecordsEveryAttempt() {
    var events = new ArrayList<ModelRouteEvent>();
    var first = new AtomicInteger();
    var router =
        router(
            failing(ModelErrorType.RATE_LIMIT, true, first),
            new FakeModelGateway(List.of(new Person("fallback")), MAPPER),
            events,
            3,
            100);
    var result = router.generate(request(), Person.class);

    assertEquals("fallback", result.value().name());
    assertEquals(1, first.get());
    assertEquals(
        List.of(ModelRouteEvent.Outcome.FAILED, ModelRouteEvent.Outcome.SUCCEEDED),
        events.stream().map(ModelRouteEvent::outcome).toList());
    assertEquals(
        List.of("primary", "fallback"), events.stream().map(ModelRouteEvent::profileId).toList());
  }

  @Test
  void permanentAndInvalidResponsesNeverFallback() {
    for (var type :
        List.of(
            ModelErrorType.AUTHENTICATION,
            ModelErrorType.INVALID_RESPONSE,
            ModelErrorType.INTERNAL)) {
      var fallback = new AtomicInteger();
      var router =
          router(
              failing(type, false, new AtomicInteger()),
              counting(new FakeModelGateway(List.of(new Person("bad")), MAPPER), fallback),
              new ArrayList<>(),
              3,
              100);
      assertThrows(ModelException.class, () -> router.generate(request(), Person.class));
      assertEquals(0, fallback.get());
    }
  }

  @Test
  void circuitOpensSkipsProviderAndAttemptBudgetIsShared() {
    var calls = new AtomicInteger();
    ModelGateway failing = failing(ModelErrorType.TIMEOUT, true, calls);
    var events = new ArrayList<ModelRouteEvent>();
    var circuit = new ProviderCircuitBreaker(1, Duration.ofMinutes(5), CLOCK);
    var profiles = profiles();
    var router =
        new RoutingModelGateway(
            profiles,
            Map.of("p1", failing, "p2", failing),
            new ModelRoutingBudget(2, 100, BigDecimal.ONE),
            circuit,
            events::add,
            CLOCK);
    assertThrows(ModelException.class, () -> router.generate(request(), Person.class));
    assertEquals(2, calls.get());
    assertThrows(ModelException.class, () -> router.generate(request(), Person.class));
    assertEquals(2, calls.get());
    assertEquals(
        2,
        events.stream()
            .filter(event -> event.outcome() == ModelRouteEvent.Outcome.CIRCUIT_OPEN)
            .count());
  }

  @Test
  void rejectsSuccessfulResponseThatExhaustsSharedBudget() {
    var expensive = new FakeModelGateway(List.of(new Person("too-large")), MAPPER);
    var router = router(expensive, expensive, new ArrayList<>(), 1, 10);
    assertThrows(ModelException.class, () -> router.generate(request(), Person.class));
  }

  private static RoutingModelGateway router(
      ModelGateway primary,
      ModelGateway fallback,
      List<ModelRouteEvent> events,
      int attempts,
      int tokens) {
    return new RoutingModelGateway(
        profiles(),
        Map.of("p1", primary, "p2", fallback),
        new ModelRoutingBudget(attempts, tokens, BigDecimal.ONE),
        new ProviderCircuitBreaker(2, Duration.ofMinutes(1), CLOCK),
        events::add,
        CLOCK);
  }

  private static ModelGateway failing(ModelErrorType type, boolean retryable, AtomicInteger calls) {
    return new ModelGateway() {
      @Override
      public <T> ModelResult<T> generate(ModelRequest request, Class<T> responseType) {
        calls.incrementAndGet();
        throw new ModelException(type, "scripted failure", retryable);
      }
    };
  }

  private static ModelGateway counting(ModelGateway delegate, AtomicInteger calls) {
    return new ModelGateway() {
      @Override
      public <T> ModelResult<T> generate(ModelRequest request, Class<T> responseType) {
        calls.incrementAndGet();
        return delegate.generate(request, responseType);
      }
    };
  }

  private static List<ModelProfile> profiles() {
    return List.of(
        new ModelProfile("primary", "p1", "model-a", Set.of("plan"), 20),
        new ModelProfile("fallback", "p2", "model-b", Set.of("plan"), 10));
  }

  private static ModelRequest request() {
    return new ModelRequest(
        "system",
        "user",
        JsonSchemas.parse("{\"type\":\"object\"}"),
        "ignored",
        0,
        Duration.ofSeconds(1),
        Map.of("phase", "plan"));
  }

  record Person(String name) {}
}

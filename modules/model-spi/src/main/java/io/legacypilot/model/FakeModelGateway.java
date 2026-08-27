package io.legacypilot.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

public final class FakeModelGateway implements ModelGateway {

  private final ArrayDeque<Object> script;
  private final ObjectMapper mapper;

  public FakeModelGateway(List<?> script, ObjectMapper mapper) {
    this.script = new ArrayDeque<>(script);
    this.mapper = Objects.requireNonNull(mapper);
  }

  @Override
  public synchronized <T> ModelResult<T> generate(ModelRequest request, Class<T> responseType) {
    if (script.isEmpty()) {
      throw new ModelException(ModelErrorType.INTERNAL, "Fake model script exhausted", false);
    }
    var next = script.removeFirst();
    if (next instanceof ModelException exception) {
      throw exception;
    }
    return new ModelResult<>(
        mapper.convertValue(next, responseType),
        new ModelUsage(10, 5, java.math.BigDecimal.ZERO, request.model()),
        Duration.ofMillis(1),
        0);
  }

  public synchronized int remaining() {
    return script.size();
  }
}

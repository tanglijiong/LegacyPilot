package io.legacypilot.model;

public interface ModelGateway {
  <T> ModelResult<T> generate(ModelRequest request, Class<T> responseType);
}

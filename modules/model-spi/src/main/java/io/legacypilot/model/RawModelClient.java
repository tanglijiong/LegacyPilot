package io.legacypilot.model;

@FunctionalInterface
public interface RawModelClient {
  RawModelResponse complete(ModelRequest request);
}

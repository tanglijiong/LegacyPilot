package io.legacypilot.context;

@FunctionalInterface
public interface EmbeddingProvider {
  EmbeddingVector embed(String text);
}

package io.legacypilot.context;

import java.util.ArrayList;
import java.util.Locale;

public final class DeterministicEmbeddingProvider implements EmbeddingProvider {
  private final String model;
  private final int dimensions;

  public DeterministicEmbeddingProvider(String model, int dimensions) {
    if (model == null || model.isBlank() || dimensions < 8 || dimensions > 4_096) {
      throw new IllegalArgumentException("deterministic embedding configuration is invalid");
    }
    this.model = model;
    this.dimensions = dimensions;
  }

  @Override
  public EmbeddingVector embed(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("embedding text must not be blank");
    }
    var values = new double[dimensions];
    var tokens = text.toLowerCase(Locale.ROOT).split("[^a-z0-9_$]+");
    for (var token : tokens) {
      if (!token.isBlank()) {
        var hash = stableHash(token);
        var index = Math.floorMod(hash, dimensions);
        values[index] += (hash & 1) == 0 ? 1.0 : -1.0;
      }
    }
    var norm = Math.sqrt(java.util.Arrays.stream(values).map(value -> value * value).sum());
    var result = new ArrayList<Double>(dimensions);
    for (var value : values) {
      result.add(norm == 0 ? 0 : value / norm);
    }
    return new EmbeddingVector(model, result);
  }

  private static int stableHash(String value) {
    var hash = 0x811c9dc5;
    for (var character : value.toCharArray()) {
      hash ^= character;
      hash *= 0x01000193;
    }
    return hash;
  }
}

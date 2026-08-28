package io.legacypilot.model;

import java.util.Objects;
import java.util.Set;

public record ModelProfile(
    String id, String provider, String model, Set<String> phases, int priority) {
  public ModelProfile {
    id = requireName(id, "profile id");
    provider = requireName(provider, "provider");
    model = requireName(model, "model");
    phases = phases == null ? Set.of() : Set.copyOf(phases);
    if (phases.stream().anyMatch(value -> value == null || value.isBlank())) {
      throw new IllegalArgumentException("model profile phases are invalid");
    }
  }

  public boolean supports(String phase) {
    return phases.isEmpty() || phases.contains(phase);
  }

  private static String requireName(String value, String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isBlank() || value.length() > 120) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return value;
  }
}

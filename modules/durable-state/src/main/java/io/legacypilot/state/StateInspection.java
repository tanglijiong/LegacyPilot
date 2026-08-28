package io.legacypilot.state;

import java.nio.file.Path;
import java.util.Objects;

public record StateInspection(Path path, StateHealth health, int schemaVersion, String detail) {
  public StateInspection {
    path = Objects.requireNonNull(path).toAbsolutePath().normalize();
    Objects.requireNonNull(health);
    detail = Objects.requireNonNullElse(detail, "");
  }
}

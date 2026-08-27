package io.legacypilot.model;

import java.time.Duration;
import java.util.Objects;

public record ModelResult<T>(T value, ModelUsage usage, Duration duration, int formatCorrections) {

  public ModelResult {
    Objects.requireNonNull(value, "value must not be null");
    Objects.requireNonNull(usage, "usage must not be null");
    Objects.requireNonNull(duration, "duration must not be null");
    if (duration.isNegative() || formatCorrections < 0) {
      throw new IllegalArgumentException("model result metadata is invalid");
    }
  }
}

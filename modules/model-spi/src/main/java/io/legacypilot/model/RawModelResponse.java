package io.legacypilot.model;

import java.time.Duration;
import java.util.Objects;

public record RawModelResponse(String content, ModelUsage usage, Duration duration) {

  public RawModelResponse {
    Objects.requireNonNull(content, "content must not be null");
    Objects.requireNonNull(usage, "usage must not be null");
    Objects.requireNonNull(duration, "duration must not be null");
  }
}

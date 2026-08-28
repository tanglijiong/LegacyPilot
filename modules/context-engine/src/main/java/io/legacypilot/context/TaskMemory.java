package io.legacypilot.context;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record TaskMemory(
    String id,
    String taskId,
    MemoryKind kind,
    String content,
    Set<String> sources,
    boolean verified,
    Instant createdAt,
    Instant expiresAt) {
  public TaskMemory {
    Objects.requireNonNull(id);
    Objects.requireNonNull(taskId);
    Objects.requireNonNull(kind);
    Objects.requireNonNull(content);
    sources = Set.copyOf(Objects.requireNonNull(sources));
    Objects.requireNonNull(createdAt);
    Objects.requireNonNull(expiresAt);
    if (id.isBlank() || taskId.isBlank() || content.isBlank() || !expiresAt.isAfter(createdAt)) {
      throw new IllegalArgumentException("task memory is invalid");
    }
  }

  public boolean activeAt(Instant instant) {
    return expiresAt.isAfter(instant);
  }
}

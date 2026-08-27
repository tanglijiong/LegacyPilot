package io.legacypilot.domain.task;

import java.util.List;
import java.util.Objects;

public record AcceptanceCriteria(List<String> items) {

  public AcceptanceCriteria {
    Objects.requireNonNull(items, "items must not be null");
    items = List.copyOf(items);
    if (items.stream().anyMatch(item -> item == null || item.isBlank())) {
      throw new IllegalArgumentException("criteria items must not be null or blank");
    }
  }

  public static AcceptanceCriteria none() {
    return new AcceptanceCriteria(List.of());
  }
}

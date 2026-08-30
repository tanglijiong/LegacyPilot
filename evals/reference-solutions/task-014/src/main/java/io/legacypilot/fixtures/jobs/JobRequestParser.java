package io.legacypilot.fixtures.jobs;

public final class JobRequestParser {
  public int parsePriority(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("INVALID_PRIORITY");
    }
    try {
      var priority = Integer.parseInt(value.trim());
      if (priority < 1 || priority > 5) {
        throw new IllegalArgumentException("INVALID_PRIORITY");
      }
      return priority;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("INVALID_PRIORITY", exception);
    }
  }
}

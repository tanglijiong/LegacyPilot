package io.legacypilot.fixtures.jobs;

public final class JobRequestParser {
  public int parsePriority(String value) {
    return Integer.parseInt(value);
  }
}

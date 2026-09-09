package io.legacypilot.domain.task;

/** Defines the outcome and safety contract for a task. */
public enum TaskMode {
  CHANGE,
  ANALYZE,
  EXPLAIN;

  public boolean readOnly() {
    return this != CHANGE;
  }
}

package io.legacypilot.runtime;

public enum RuntimeStatus {
  PLANNING,
  EXECUTING,
  WAITING_FOR_APPROVAL,
  VERIFYING,
  SUCCEEDED,
  FAILED,
  DENIED,
  BUDGET_EXHAUSTED;

  public boolean terminal() {
    return this == SUCCEEDED || this == FAILED || this == DENIED || this == BUDGET_EXHAUSTED;
  }
}

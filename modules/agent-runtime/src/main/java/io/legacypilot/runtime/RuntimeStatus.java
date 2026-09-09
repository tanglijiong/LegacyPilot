package io.legacypilot.runtime;

public enum RuntimeStatus {
  PLANNING,
  EXECUTING,
  WAITING_FOR_APPROVAL,
  NEEDS_REVIEW,
  LEASE_CONFLICT,
  VERIFYING,
  SUCCEEDED,
  FAILED,
  DENIED,
  CANCELLED,
  BUDGET_EXHAUSTED;

  public boolean terminal() {
    return this == SUCCEEDED
        || this == FAILED
        || this == DENIED
        || this == CANCELLED
        || this == BUDGET_EXHAUSTED;
  }
}

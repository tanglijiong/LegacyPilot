package io.legacypilot.domain.run;

public enum RunStatus {
  CREATED,
  PREPARING_WORKSPACE,
  WORKSPACE_READY,
  PLANNING,
  WAITING_FOR_APPROVAL,
  EXECUTING,
  VERIFYING,
  NEEDS_REVIEW,
  RECOVERING,
  SUCCEEDED,
  FAILED,
  CANCELLED;

  public boolean isTerminal() {
    return this == SUCCEEDED || this == FAILED || this == CANCELLED;
  }
}

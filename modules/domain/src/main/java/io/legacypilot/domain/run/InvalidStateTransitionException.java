package io.legacypilot.domain.run;

public final class InvalidStateTransitionException extends IllegalStateException {

  public InvalidStateTransitionException(RunStatus from, RunStatus to) {
    super("Cannot transition task run from " + from + " to " + to);
  }
}

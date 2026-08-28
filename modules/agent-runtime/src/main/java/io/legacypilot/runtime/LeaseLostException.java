package io.legacypilot.runtime;

public final class LeaseLostException extends IllegalStateException {
  public LeaseLostException(String message) {
    super(message);
  }
}

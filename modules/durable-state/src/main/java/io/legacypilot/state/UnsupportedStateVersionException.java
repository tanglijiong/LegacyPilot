package io.legacypilot.state;

public final class UnsupportedStateVersionException extends IllegalStateException {
  public UnsupportedStateVersionException(String message) {
    super(message);
  }
}

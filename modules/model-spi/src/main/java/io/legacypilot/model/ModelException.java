package io.legacypilot.model;

import java.util.Objects;

public final class ModelException extends RuntimeException {

  private final ModelErrorType type;
  private final boolean retryable;

  public ModelException(ModelErrorType type, String message, boolean retryable) {
    super(message);
    this.type = Objects.requireNonNull(type);
    this.retryable = retryable;
  }

  public ModelException(ModelErrorType type, String message, boolean retryable, Throwable cause) {
    super(message, cause);
    this.type = Objects.requireNonNull(type);
    this.retryable = retryable;
  }

  public ModelErrorType type() {
    return type;
  }

  public boolean retryable() {
    return retryable;
  }
}

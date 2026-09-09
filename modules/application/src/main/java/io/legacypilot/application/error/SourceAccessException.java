package io.legacypilot.application.error;

/** A fail-closed rejection while resolving source content for a registered project. */
public final class SourceAccessException extends RuntimeException {

  private final Reason reason;

  public SourceAccessException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public SourceAccessException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }

  public enum Reason {
    INVALID_PATH,
    REVISION_MISMATCH,
    FILE_NOT_FOUND,
    SYMBOLIC_LINK,
    BINARY_FILE,
    FILE_TOO_LARGE,
    RANGE_TOO_LARGE,
    READ_FAILED
  }
}

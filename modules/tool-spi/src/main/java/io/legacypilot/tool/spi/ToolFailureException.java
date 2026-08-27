package io.legacypilot.tool.spi;

public final class ToolFailureException extends RuntimeException {

  private final ToolErrorCode code;

  public ToolFailureException(ToolErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public ToolFailureException(ToolErrorCode code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public ToolErrorCode code() {
    return code;
  }
}

package io.legacypilot.samples.banking;

public final class TransferLimitException extends RuntimeException {
  public TransferLimitException() {
    super("TRANSFER_LIMIT_EXCEEDED");
  }
}

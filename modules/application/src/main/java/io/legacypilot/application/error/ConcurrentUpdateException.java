package io.legacypilot.application.error;

public final class ConcurrentUpdateException extends RuntimeException {
  public ConcurrentUpdateException(String resource, String id) {
    super(resource + " was updated concurrently: " + id);
  }
}

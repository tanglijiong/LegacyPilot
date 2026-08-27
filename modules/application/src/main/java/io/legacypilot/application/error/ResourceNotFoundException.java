package io.legacypilot.application.error;

public final class ResourceNotFoundException extends RuntimeException {
  public ResourceNotFoundException(String resource, String id) {
    super(resource + " not found: " + id);
  }
}

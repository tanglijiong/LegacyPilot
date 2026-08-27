package io.legacypilot.sandbox;

public interface SandboxExecutor {
  SandboxResult execute(SandboxRequest request);

  boolean cancel(String executionId);

  boolean available();
}

package io.legacypilot.sandbox;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record SandboxRequest(
    String executionId,
    String image,
    Path workspace,
    Path dependencyCache,
    List<String> command,
    Map<String, String> environment,
    SandboxLimits limits,
    SandboxPhase phase) {

  public SandboxRequest(
      String executionId,
      String image,
      Path workspace,
      Path dependencyCache,
      List<String> command,
      Map<String, String> environment,
      SandboxLimits limits) {
    this(
        executionId,
        image,
        workspace,
        dependencyCache,
        command,
        environment,
        limits,
        SandboxPhase.EXECUTION);
  }

  public SandboxRequest {
    Objects.requireNonNull(executionId, "executionId must not be null");
    Objects.requireNonNull(image, "image must not be null");
    Objects.requireNonNull(workspace, "workspace must not be null");
    Objects.requireNonNull(command, "command must not be null");
    Objects.requireNonNull(environment, "environment must not be null");
    Objects.requireNonNull(limits, "limits must not be null");
    Objects.requireNonNull(phase, "sandbox phase must not be null");
    if (!executionId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")) {
      throw new IllegalArgumentException("executionId is invalid");
    }
    if (image.isBlank() || command.isEmpty()) {
      throw new IllegalArgumentException("image and command must not be empty");
    }
    if (command.stream().anyMatch(value -> value == null || value.indexOf('\0') >= 0)) {
      throw new IllegalArgumentException("command arguments must not contain null or NUL");
    }
    environment.forEach(
        (key, value) -> {
          if (!key.matches("[A-Z][A-Z0-9_]{0,63}") || value == null || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("environment entry is invalid");
          }
        });
    workspace = workspace.toAbsolutePath().normalize();
    dependencyCache = dependencyCache == null ? null : dependencyCache.toAbsolutePath().normalize();
    if (phase == SandboxPhase.DEPENDENCY_PREWARM && dependencyCache == null) {
      throw new IllegalArgumentException("dependency prewarm requires a cache");
    }
    command = List.copyOf(command);
    environment = Map.copyOf(environment);
  }
}

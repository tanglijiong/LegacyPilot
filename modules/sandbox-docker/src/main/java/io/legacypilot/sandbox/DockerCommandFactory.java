package io.legacypilot.sandbox;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class DockerCommandFactory {

  private final String dockerExecutable;
  private final Set<String> allowedImages;
  private final Set<String> allowedExecutables;
  private final Set<String> allowedEnvironment;

  DockerCommandFactory(
      Set<String> allowedImages, Set<String> allowedExecutables, Set<String> allowedEnvironment) {
    this("docker", allowedImages, allowedExecutables, allowedEnvironment);
  }

  DockerCommandFactory(
      String dockerExecutable,
      Set<String> allowedImages,
      Set<String> allowedExecutables,
      Set<String> allowedEnvironment) {
    this.dockerExecutable = dockerExecutable;
    this.allowedImages = Set.copyOf(allowedImages);
    this.allowedExecutables = Set.copyOf(allowedExecutables);
    this.allowedEnvironment = Set.copyOf(allowedEnvironment);
  }

  List<String> create(String containerName, SandboxRequest request) {
    if (!allowedImages.contains(request.image())) {
      throw new IllegalArgumentException("sandbox image is not allowlisted");
    }
    if (!allowedExecutables.contains(request.command().getFirst())) {
      throw new IllegalArgumentException("sandbox executable is not allowlisted");
    }
    if (!allowedEnvironment.containsAll(request.environment().keySet())) {
      throw new IllegalArgumentException("sandbox environment key is not allowlisted");
    }
    if (!Files.isDirectory(request.workspace())) {
      throw new IllegalArgumentException("workspace must be an existing directory");
    }
    validateMountPath(request.workspace().toString());
    if (request.dependencyCache() != null) {
      if (!Files.isDirectory(request.dependencyCache())) {
        throw new IllegalArgumentException("dependency cache must be an existing directory");
      }
      validateMountPath(request.dependencyCache().toString());
    }

    var limits = request.limits();
    var command = new ArrayList<String>();
    command.addAll(
        List.of(
            dockerExecutable,
            "run",
            "--rm",
            "--name",
            containerName,
            "--network",
            "none",
            "--read-only",
            "--cap-drop",
            "ALL",
            "--security-opt",
            "no-new-privileges",
            "--pids-limit",
            Integer.toString(limits.pids()),
            "--memory",
            Long.toString(limits.memoryBytes()),
            "--memory-swap",
            Long.toString(limits.memoryBytes()),
            "--cpus",
            String.format(Locale.ROOT, "%.2f", limits.cpus()),
            "--user",
            "1000:1000",
            "--workdir",
            "/workspace",
            "--tmpfs",
            "/tmp:rw,noexec,nosuid,nodev,size=" + limits.temporaryStorageBytes(),
            "--mount",
            "type=bind,src=" + request.workspace() + ",dst=/workspace,rw",
            "--env",
            "HOME=/tmp/home"));
    if (request.dependencyCache() != null) {
      command.addAll(
          List.of(
              "--mount",
              "type=bind,src=" + request.dependencyCache() + ",dst=/maven-cache,readonly"));
    }
    request.environment().entrySet().stream()
        .sorted(java.util.Map.Entry.comparingByKey())
        .forEach(
            entry -> command.addAll(List.of("--env", entry.getKey() + "=" + entry.getValue())));
    command.add(request.image());
    command.addAll(request.command());
    return List.copyOf(command);
  }

  private static void validateMountPath(String value) {
    if (value.indexOf(',') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
      throw new IllegalArgumentException("mount path contains unsupported characters");
    }
  }
}

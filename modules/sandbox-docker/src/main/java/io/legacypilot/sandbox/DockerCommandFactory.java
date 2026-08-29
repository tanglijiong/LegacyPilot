package io.legacypilot.sandbox;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class DockerCommandFactory {

  private final String dockerExecutable;
  private final DockerImagePolicy imagePolicy;
  private final Set<String> allowedExecutables;
  private final Set<String> allowedEnvironment;
  private final boolean networkedPrewarmAllowed;

  DockerCommandFactory(
      Set<String> allowedImages, Set<String> allowedExecutables, Set<String> allowedEnvironment) {
    this(
        "docker",
        DockerImagePolicy.allowlisted(allowedImages),
        allowedExecutables,
        allowedEnvironment,
        false);
  }

  DockerCommandFactory(
      String dockerExecutable,
      Set<String> allowedImages,
      Set<String> allowedExecutables,
      Set<String> allowedEnvironment) {
    this(
        dockerExecutable,
        DockerImagePolicy.allowlisted(allowedImages),
        allowedExecutables,
        allowedEnvironment,
        false);
  }

  DockerCommandFactory(
      String dockerExecutable,
      DockerImagePolicy imagePolicy,
      Set<String> allowedExecutables,
      Set<String> allowedEnvironment,
      boolean networkedPrewarmAllowed) {
    this.dockerExecutable = dockerExecutable;
    this.imagePolicy = imagePolicy;
    this.allowedExecutables = Set.copyOf(allowedExecutables);
    this.allowedEnvironment = Set.copyOf(allowedEnvironment);
    this.networkedPrewarmAllowed = networkedPrewarmAllowed;
  }

  List<String> create(String containerName, SandboxRequest request) {
    imagePolicy.validate(request.image());
    if (!allowedExecutables.contains(request.command().getFirst())) {
      throw new IllegalArgumentException("sandbox executable is not allowlisted");
    }
    if (!allowedEnvironment.containsAll(request.environment().keySet())) {
      throw new IllegalArgumentException("sandbox environment key is not allowlisted");
    }
    var workspace = request.workspace().toAbsolutePath().normalize();
    if (!Files.isDirectory(workspace)) {
      throw new IllegalArgumentException("workspace must be an existing directory");
    }
    validateMountPath(workspace.toString());
    var dependencyCache =
        request.dependencyCache() == null
            ? null
            : request.dependencyCache().toAbsolutePath().normalize();
    if (dependencyCache != null) {
      if (!Files.isDirectory(dependencyCache)) {
        throw new IllegalArgumentException("dependency cache must be an existing directory");
      }
      validateMountPath(dependencyCache.toString());
    }
    if (request.phase() == SandboxPhase.DEPENDENCY_PREWARM && !networkedPrewarmAllowed) {
      throw new IllegalArgumentException("networked dependency prewarm is disabled");
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
            request.phase() == SandboxPhase.DEPENDENCY_PREWARM ? "bridge" : "none",
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
            "type=bind,src=" + workspace + ",dst=/workspace",
            "--env",
            "HOME=/tmp/home",
            "--env",
            "MAVEN_CONFIG=/tmp/home/.m2"));
    if (dependencyCache != null) {
      command.addAll(
          List.of(
              "--mount",
              "type=bind,src="
                  + dependencyCache
                  + ",dst=/maven-cache"
                  + (request.phase() == SandboxPhase.DEPENDENCY_PREWARM ? "" : ",readonly")));
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

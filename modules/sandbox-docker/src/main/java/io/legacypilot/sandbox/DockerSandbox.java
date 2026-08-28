package io.legacypilot.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DockerSandbox implements SandboxExecutor {

  public static final String DEFAULT_MAVEN_IMAGE = "maven:3.9.16-eclipse-temurin-21";

  private final String dockerExecutable;
  private final DockerCommandFactory commands;
  private final Map<String, RunningExecution> running = new ConcurrentHashMap<>();

  public DockerSandbox(
      Set<String> allowedImages, Set<String> allowedExecutables, Set<String> allowedEnvironment) {
    this("docker", allowedImages, allowedExecutables, allowedEnvironment);
  }

  DockerSandbox(
      String dockerExecutable,
      Set<String> allowedImages,
      Set<String> allowedExecutables,
      Set<String> allowedEnvironment) {
    this.dockerExecutable = dockerExecutable;
    this.commands =
        new DockerCommandFactory(
            dockerExecutable, allowedImages, allowedExecutables, allowedEnvironment);
  }

  public DockerSandbox(
      String dockerExecutable,
      DockerImagePolicy imagePolicy,
      Set<String> allowedExecutables,
      Set<String> allowedEnvironment,
      boolean networkedPrewarmAllowed) {
    this.dockerExecutable = dockerExecutable;
    this.commands =
        new DockerCommandFactory(
            dockerExecutable,
            imagePolicy,
            allowedExecutables,
            allowedEnvironment,
            networkedPrewarmAllowed);
  }

  public static DockerSandbox secureMavenDefaults() {
    return new DockerSandbox(
        Set.of(DEFAULT_MAVEN_IMAGE), Set.of("mvn"), Set.of("MAVEN_OPTS", "JAVA_TOOL_OPTIONS"));
  }

  @Override
  public SandboxResult execute(SandboxRequest request) {
    var executionId = request.executionId();
    var started = Instant.now();
    if (!available()) {
      return result(
          executionId,
          SandboxStatus.UNAVAILABLE,
          null,
          "Docker daemon is unavailable",
          started,
          false);
    }
    if (directorySize(request.workspace()) > request.limits().workspaceBytes()) {
      return result(
          executionId,
          SandboxStatus.RESOURCE_LIMIT,
          null,
          "Workspace exceeds its configured size limit",
          started,
          false);
    }
    var name = "legacypilot-" + executionId;
    try {
      var process =
          new ProcessBuilder(commands.create(name, request)).redirectErrorStream(true).start();
      var state = new RunningExecution(name, process, new AtomicBoolean());
      if (running.putIfAbsent(executionId, state) != null) {
        process.destroyForcibly();
        return result(
            executionId,
            SandboxStatus.FAILED,
            null,
            "Execution id is already running",
            started,
            false);
      }
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var outputFuture =
            executor.submit(() -> readOutput(process, request.limits().maxOutputBytes()));
        if (!process.waitFor(request.limits().timeout().toMillis(), TimeUnit.MILLISECONDS)) {
          forceRemove(name);
          process.destroyForcibly();
          process.waitFor();
          var output = outputFuture.get();
          return result(
              executionId,
              SandboxStatus.TIMED_OUT,
              null,
              output.text(),
              started,
              output.truncated());
        }
        var output = outputFuture.get();
        var status =
            state.cancelled().get()
                ? SandboxStatus.CANCELLED
                : process.exitValue() == 0 ? SandboxStatus.SUCCESS : SandboxStatus.FAILED;
        if (directorySize(request.workspace()) > request.limits().workspaceBytes()) {
          status = SandboxStatus.RESOURCE_LIMIT;
        }
        return result(
            executionId, status, process.exitValue(), output.text(), started, output.truncated());
      } finally {
        running.remove(executionId);
      }
    } catch (IOException exception) {
      return result(
          executionId,
          SandboxStatus.UNAVAILABLE,
          null,
          "Unable to start Docker CLI",
          started,
          false);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      forceRemove(name);
      return result(
          executionId,
          SandboxStatus.CANCELLED,
          null,
          "Sandbox execution interrupted",
          started,
          false);
    } catch (ExecutionException exception) {
      forceRemove(name);
      return result(
          executionId,
          SandboxStatus.FAILED,
          null,
          "Unable to collect sandbox output",
          started,
          false);
    }
  }

  @Override
  public boolean cancel(String executionId) {
    var state = running.get(executionId);
    if (state == null) {
      return false;
    }
    state.cancelled().set(true);
    forceRemove(state.containerName());
    state.process().destroyForcibly();
    return true;
  }

  @Override
  public boolean available() {
    try {
      var process =
          new ProcessBuilder(List.of(dockerExecutable, "info", "--format", "{{.ServerVersion}}"))
              .redirectErrorStream(true)
              .start();
      return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
    } catch (IOException exception) {
      return false;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static Output readOutput(Process process, int maximumBytes) throws IOException {
    var output = new byte[maximumBytes];
    var total = 0;
    var truncated = false;
    try (var stream = process.getInputStream()) {
      int value;
      while ((value = stream.read()) >= 0) {
        if (total < maximumBytes) {
          output[total++] = (byte) value;
        } else {
          truncated = true;
        }
      }
    }
    return new Output(
        new String(output, 0, total, java.nio.charset.StandardCharsets.UTF_8), truncated);
  }

  private static long directorySize(Path root) {
    try (var paths = Files.walk(root)) {
      return paths.filter(Files::isRegularFile).mapToLong(DockerSandbox::fileSize).sum();
    } catch (IOException exception) {
      throw new IllegalArgumentException("Unable to measure workspace size", exception);
    }
  }

  private static long fileSize(Path path) {
    try {
      return Files.size(path);
    } catch (IOException exception) {
      throw new IllegalArgumentException("Unable to measure workspace file", exception);
    }
  }

  private void forceRemove(String name) {
    try {
      var process =
          new ProcessBuilder(List.of(dockerExecutable, "rm", "--force", name))
              .redirectErrorStream(true)
              .start();
      process.waitFor(10, TimeUnit.SECONDS);
    } catch (IOException exception) {
      // The daemon may already be unavailable; there is no further local cleanup action.
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  private static SandboxResult result(
      String executionId,
      SandboxStatus status,
      Integer exitCode,
      String output,
      Instant started,
      boolean truncated) {
    return new SandboxResult(
        executionId,
        status,
        exitCode,
        redact(output),
        Duration.between(started, Instant.now()),
        truncated);
  }

  private static String redact(String output) {
    return output.replaceAll(
        "(?i)(token|password|secret|api[_-]?key)(\\s*[:=]\\s*)[^\\s]+", "$1$2[REDACTED]");
  }

  private record Output(String text, boolean truncated) {}

  private record RunningExecution(String containerName, Process process, AtomicBoolean cancelled) {}
}

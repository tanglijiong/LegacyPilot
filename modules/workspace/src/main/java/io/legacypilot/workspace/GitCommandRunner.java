package io.legacypilot.workspace;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class GitCommandRunner {

  private final Duration timeout;
  private final int maxOutputCharacters;

  GitCommandRunner(Duration timeout, int maxOutputCharacters) {
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    if (maxOutputCharacters < 1024) {
      throw new IllegalArgumentException("maxOutputCharacters must be at least 1024");
    }
    this.timeout = timeout;
    this.maxOutputCharacters = maxOutputCharacters;
  }

  String run(Path workingDirectory, List<String> arguments) {
    var command = new ArrayList<String>();
    command.add("git");
    command.addAll(arguments);
    if (command.stream().anyMatch(value -> value == null || value.indexOf('\0') >= 0)) {
      throw new IllegalArgumentException("Git arguments must not contain null values or NUL bytes");
    }

    try {
      var builder = new ProcessBuilder(command).redirectErrorStream(true);
      if (workingDirectory != null) {
        builder.directory(workingDirectory.toFile());
      }
      var process = builder.start();
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var outputFuture = executor.submit(() -> readOutput(process));
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
          process.destroyForcibly();
          process.waitFor();
          throw new WorkspaceException("Git command timed out: " + safeDescription(arguments));
        }
        var output = outputFuture.get();
        if (process.exitValue() != 0) {
          throw new WorkspaceException(
              "Git command failed (exit "
                  + process.exitValue()
                  + "): "
                  + safeDescription(arguments)
                  + "\n"
                  + output);
        }
        return output.strip();
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new WorkspaceException("Git command interrupted", exception);
    } catch (IOException exception) {
      throw new WorkspaceException("Unable to start Git", exception);
    } catch (java.util.concurrent.ExecutionException exception) {
      throw new WorkspaceException("Unable to read Git output", exception.getCause());
    }
  }

  private String readOutput(Process process) throws IOException {
    var output = new StringBuilder();
    try (var reader = process.inputReader()) {
      int value;
      while ((value = reader.read()) >= 0) {
        if (output.length() < maxOutputCharacters) {
          output.append((char) value);
        }
      }
    }
    if (output.length() == maxOutputCharacters) {
      output.append("\n[output truncated]");
    }
    return output.toString();
  }

  private static String safeDescription(List<String> arguments) {
    return arguments.isEmpty() ? "git" : "git " + arguments.getFirst();
  }
}

package io.legacypilot.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class JsonlProcessModelAdapter implements EvalModelAdapter {
  private static final int MAXIMUM_PROCESS_OUTPUT_BYTES = 8 * 1024 * 1024;

  @FunctionalInterface
  interface CommandFactory {
    List<String> create(Path workspace, EvalTask task);
  }

  private final String adapterId;
  private final NetworkBoundary boundary;
  private final CommandFactory commands;
  private final ObjectMapper mapper;

  JsonlProcessModelAdapter(
      String adapterId, NetworkBoundary boundary, CommandFactory commands, ObjectMapper mapper) {
    this.adapterId = requireText(adapterId);
    this.boundary = Objects.requireNonNull(boundary);
    this.commands = Objects.requireNonNull(commands);
    this.mapper = Objects.requireNonNull(mapper);
  }

  @Override
  public EvalModelInvocation invoke(Path workspace, EvalTask task, String prompt) {
    var command = List.copyOf(commands.create(workspace, task));
    if (command.isEmpty() || prompt == null || prompt.isBlank()) {
      throw new IllegalArgumentException("model process invocation is invalid");
    }
    try {
      var builder =
          new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(false);
      scrubSensitiveEnvironment(builder.environment());
      var process = builder.start();
      try (var input = process.getOutputStream()) {
        input.write(prompt.getBytes(StandardCharsets.UTF_8));
      }
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var stdout = executor.submit(() -> readBounded(process.getInputStream()));
        var stderr = executor.submit(() -> readBounded(process.getErrorStream()));
        if (!process.waitFor(task.timeoutSeconds(), TimeUnit.SECONDS)) {
          process.destroyForcibly();
          process.waitFor(10, TimeUnit.SECONDS);
          stdout.get();
          stderr.get();
          return new EvalModelInvocation(124, EvalTokenUsage.NONE, 0);
        }
        var output = stdout.get();
        stderr.get();
        return parse(process.exitValue(), output);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("model process was interrupted", exception);
    } catch (IOException | java.util.concurrent.ExecutionException exception) {
      throw new IllegalStateException("unable to execute model process", exception);
    }
  }

  @Override
  public String adapterId() {
    return adapterId;
  }

  @Override
  public NetworkBoundary networkBoundary() {
    return boundary;
  }

  private EvalModelInvocation parse(int exitCode, String output) {
    var usage = EvalTokenUsage.NONE;
    var steps = 0;
    for (var line : output.lines().toList()) {
      try {
        var event = mapper.readTree(line);
        if (event.path("type").asText().equals("item.completed")) {
          steps++;
        }
        var candidate = event.path("usage");
        if (candidate.isObject()) {
          usage =
              new EvalTokenUsage(
                  integer(candidate, "input_tokens"),
                  integer(candidate, "cached_input_tokens"),
                  integer(candidate, "output_tokens"),
                  integer(candidate, "reasoning_output_tokens"));
        }
      } catch (IOException | IllegalArgumentException ignored) {
        // Non-event output and incomplete usage records do not invalidate verification.
      }
    }
    return new EvalModelInvocation(exitCode, usage, steps);
  }

  static void scrubSensitiveEnvironment(java.util.Map<String, String> environment) {
    var removed = new ArrayList<String>();
    for (var key : environment.keySet()) {
      var normalized = key.toLowerCase(java.util.Locale.ROOT);
      if (normalized.contains("token")
          || normalized.contains("secret")
          || normalized.contains("password")
          || normalized.contains("api_key")
          || normalized.contains("apikey")
          || normalized.contains("authorization")
          || normalized.endsWith("_proxy")) {
        removed.add(key);
      }
    }
    removed.forEach(environment::remove);
    environment.put("NO_PROXY", "*");
  }

  private static int integer(JsonNode node, String name) {
    return Math.max(0, node.path(name).asInt(0));
  }

  private static String readBounded(InputStream input) throws IOException {
    var output = new ByteArrayOutputStream();
    var buffer = new byte[8192];
    var retained = 0;
    int read;
    while ((read = input.read(buffer)) >= 0) {
      var remaining = MAXIMUM_PROCESS_OUTPUT_BYTES - retained;
      if (remaining > 0) {
        var copied = Math.min(read, remaining);
        output.write(buffer, 0, copied);
        retained += copied;
      }
    }
    return output.toString(StandardCharsets.UTF_8);
  }

  private static String requireText(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("adapter id is required");
    }
    return value;
  }
}

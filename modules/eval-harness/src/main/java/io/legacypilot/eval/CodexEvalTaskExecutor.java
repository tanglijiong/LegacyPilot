package io.legacypilot.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class CodexEvalTaskExecutor implements EvalTaskExecutor {
  private static final int MAXIMUM_PROCESS_OUTPUT_BYTES = 8 * 1024 * 1024;

  private final Map<String, Path> fixtures;
  private final Path workspaceRoot;
  private final Path referenceSolutions;
  private final Path executable;
  private final String model;
  private final String reasoningEffort;
  private final String promptTemplate;
  private final EvalPricingSnapshot pricing;
  private final FixtureVerifier verifier;
  private final ObjectMapper mapper;
  private final WorkspaceIntegrityGuard integrity = new WorkspaceIntegrityGuard();
  private final SourceAssertionEngine assertions = new SourceAssertionEngine();

  public CodexEvalTaskExecutor(
      Map<String, Path> fixtures,
      Path workspaceRoot,
      Path referenceSolutions,
      Path executable,
      String model,
      String reasoningEffort,
      String promptTemplate,
      EvalPricingSnapshot pricing,
      FixtureVerifier verifier,
      ObjectMapper mapper) {
    var normalized = new java.util.LinkedHashMap<String, Path>();
    Objects.requireNonNull(fixtures)
        .forEach(
            (id, path) ->
                normalized.put(
                    Objects.requireNonNull(id),
                    Objects.requireNonNull(path).toAbsolutePath().normalize()));
    this.fixtures = Map.copyOf(normalized);
    this.workspaceRoot = Objects.requireNonNull(workspaceRoot).toAbsolutePath().normalize();
    this.referenceSolutions =
        Objects.requireNonNull(referenceSolutions).toAbsolutePath().normalize();
    this.executable = Objects.requireNonNull(executable).toAbsolutePath().normalize();
    this.model = Objects.requireNonNull(model);
    this.reasoningEffort = Objects.requireNonNull(reasoningEffort);
    this.promptTemplate = Objects.requireNonNull(promptTemplate);
    this.pricing = Objects.requireNonNull(pricing);
    this.verifier = Objects.requireNonNull(verifier);
    this.mapper = Objects.requireNonNull(mapper);
    if (this.fixtures.isEmpty()
        || !Files.isRegularFile(this.executable)
        || model.isBlank()
        || !reasoningEffort.matches("low|medium|high|xhigh|max|ultra")
        || !promptTemplate.contains("{{requirement}}")) {
      throw new IllegalArgumentException("Codex eval executor configuration is invalid");
    }
  }

  @Override
  public EvalTaskResult execute(EvalTask task) {
    var fixture = fixtures.get(task.fixtureId());
    if (fixture == null) {
      throw new IllegalArgumentException("eval task fixture is unavailable");
    }
    var workspace = workspaceRoot.resolve(task.id()).normalize();
    if (!workspace.startsWith(workspaceRoot) || Files.exists(workspace)) {
      throw new IllegalStateException("eval task workspace already exists or is invalid");
    }
    copy(fixture, workspace);
    var baseline = integrity.capture(workspace);
    var started = System.nanoTime();
    var processResult = invoke(workspace, task);
    var duration = Duration.ofNanos(System.nanoTime() - started);
    var integrityResult = integrity.verify(baseline, workspace, task);
    var assertionResult = assertions.evaluate(workspace, task.assertions());
    overlayHiddenTests(task, workspace);
    var verification = verifier.verify(workspace);
    var passed =
        processResult.exitCode() == 0
            && integrityResult.successful()
            && assertionResult.successful()
            && verification.compiled()
            && verification.testsPassed();
    var failure = new ArrayList<String>();
    if (processResult.exitCode() != 0) {
      failure.add("agent process exited with code " + processResult.exitCode());
    }
    failure.addAll(integrityResult.violations());
    failure.addAll(assertionResult.failures());
    if (!verification.summary().isBlank() && !verification.testsPassed()) {
      failure.add(verification.summary());
    }
    var usage = processResult.usage();
    return new EvalTaskResult(
        task.id(),
        processResult.exitCode() != 0
            ? EvalTaskResult.Status.ERROR
            : passed ? EvalTaskResult.Status.PASSED : EvalTaskResult.Status.FAILED,
        assertionResult.passed(),
        assertionResult.total(),
        verification.compiled(),
        verification.testsPassed(),
        0,
        processResult.steps(),
        usage,
        pricing.price(usage),
        duration,
        integrityResult.changedFiles(),
        String.join("; ", failure));
  }

  private ProcessResult invoke(Path workspace, EvalTask task) {
    var command =
        List.of(
            executable.toString(),
            "exec",
            "--ephemeral",
            "--ignore-user-config",
            "--ignore-rules",
            "--skip-git-repo-check",
            "--sandbox",
            "workspace-write",
            "--cd",
            workspace.toString(),
            "--json",
            "--model",
            model,
            "--config",
            "model_reasoning_effort=\"" + reasoningEffort + "\"",
            "-");
    try {
      var process =
          new ProcessBuilder(command)
              .directory(workspace.toFile())
              .redirectErrorStream(false)
              .start();
      var prompt = promptTemplate.replace("{{requirement}}", task.requirement());
      process.getOutputStream().write(prompt.getBytes(StandardCharsets.UTF_8));
      process.getOutputStream().close();
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var stdout = executor.submit(() -> readBounded(process.getInputStream()));
        var stderr = executor.submit(() -> readBounded(process.getErrorStream()));
        if (!process.waitFor(task.timeoutSeconds(), TimeUnit.SECONDS)) {
          process.destroyForcibly();
          process.waitFor(10, TimeUnit.SECONDS);
          stdout.get();
          stderr.get();
          return new ProcessResult(124, EvalTokenUsage.NONE, 0);
        }
        var output = stdout.get();
        stderr.get();
        return parse(process.exitValue(), output);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Codex eval execution was interrupted", exception);
    } catch (IOException | java.util.concurrent.ExecutionException exception) {
      throw new IllegalStateException("unable to execute Codex eval task", exception);
    }
  }

  private ProcessResult parse(int exitCode, String output) {
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
        // Non-event output and incomplete usage records do not invalidate task verification.
      }
    }
    return new ProcessResult(exitCode, usage, steps);
  }

  private static int integer(JsonNode node, String name) {
    return Math.max(0, node.path(name).asInt(0));
  }

  private void overlayHiddenTests(EvalTask task, Path workspace) {
    var tests = referenceSolutions.resolve(task.id()).resolve("src/test");
    if (Files.isDirectory(tests)) {
      copy(tests, workspace.resolve("src/test"));
    }
  }

  private static void copy(Path source, Path target) {
    try (var paths = Files.walk(source)) {
      for (var path : paths.toList()) {
        if (Files.isSymbolicLink(path)) {
          throw new IllegalArgumentException("eval fixture contains a symbolic link");
        }
        var destination = target.resolve(source.relativize(path).toString());
        if (Files.isDirectory(path)) {
          Files.createDirectories(destination);
        } else {
          Files.createDirectories(Objects.requireNonNull(destination.getParent()));
          Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
        }
      }
    } catch (IOException exception) {
      throw new IllegalStateException("unable to prepare eval task workspace", exception);
    }
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

  private record ProcessResult(int exitCode, EvalTokenUsage usage, int steps) {}
}

package io.legacypilot.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

/** Provider-neutral fixture, integrity, hidden-test and pricing lifecycle. */
public final class GovernedEvalTaskExecutor implements EvalTaskExecutor {
  private final Map<String, Path> fixtures;
  private final Path workspaceRoot;
  private final Path referenceSolutions;
  private final String promptTemplate;
  private final EvalPricingSnapshot pricing;
  private final FixtureVerifier verifier;
  private final EvalModelAdapter modelAdapter;
  private final WorkspaceIntegrityGuard integrity = new WorkspaceIntegrityGuard();
  private final SourceAssertionEngine assertions = new SourceAssertionEngine();

  public GovernedEvalTaskExecutor(
      Map<String, Path> fixtures,
      Path workspaceRoot,
      Path referenceSolutions,
      String promptTemplate,
      EvalPricingSnapshot pricing,
      FixtureVerifier verifier,
      EvalModelAdapter modelAdapter) {
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
    this.promptTemplate = Objects.requireNonNull(promptTemplate);
    this.pricing = Objects.requireNonNull(pricing);
    this.verifier = Objects.requireNonNull(verifier);
    this.modelAdapter = Objects.requireNonNull(modelAdapter);
    if (this.fixtures.isEmpty() || !promptTemplate.contains("{{requirement}}")) {
      throw new IllegalArgumentException("governed eval executor configuration is invalid");
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
    var prompt = promptTemplate.replace("{{requirement}}", task.requirement());
    var invocation = modelAdapter.invoke(workspace, task, prompt);
    var duration = Duration.ofNanos(System.nanoTime() - started);
    var integrityResult = integrity.verify(baseline, workspace, task);
    var assertionResult = assertions.evaluate(workspace, task.assertions());
    overlayHiddenTests(task, workspace);
    var verification = verifier.verify(workspace);
    var passed =
        invocation.exitCode() == 0
            && integrityResult.successful()
            && assertionResult.successful()
            && verification.compiled()
            && verification.testsPassed();
    var failure = new ArrayList<String>();
    if (invocation.exitCode() != 0) {
      failure.add("agent process exited with code " + invocation.exitCode());
    }
    failure.addAll(integrityResult.violations());
    failure.addAll(assertionResult.failures());
    if (!verification.summary().isBlank() && !verification.testsPassed()) {
      failure.add(verification.summary());
    }
    var usage = invocation.usage();
    return new EvalTaskResult(
        task.id(),
        invocation.exitCode() != 0
            ? EvalTaskResult.Status.ERROR
            : passed ? EvalTaskResult.Status.PASSED : EvalTaskResult.Status.FAILED,
        assertionResult.passed(),
        assertionResult.total(),
        verification.compiled(),
        verification.testsPassed(),
        0,
        invocation.steps(),
        usage,
        pricing.price(usage),
        duration,
        integrityResult.changedFiles(),
        String.join("; ", failure));
  }

  public String adapterId() {
    return modelAdapter.adapterId();
  }

  public NetworkBoundary networkBoundary() {
    return modelAdapter.networkBoundary();
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
}

package io.legacypilot.eval;

import io.legacypilot.analysis.java.JavaProjectIndexer;
import io.legacypilot.context.HybridRetriever;
import io.legacypilot.context.RetrievalEvaluator;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class ReferenceBaselineExecutor implements EvalTaskExecutor {
  private final Path fixture;
  private final Path referenceSolutions;
  private final FixtureVerifier verifier;
  private final SourceAssertionEngine assertions = new SourceAssertionEngine();

  public ReferenceBaselineExecutor(
      Path fixture, Path referenceSolutions, FixtureVerifier verifier) {
    this.fixture = Objects.requireNonNull(fixture).toAbsolutePath().normalize();
    this.referenceSolutions =
        Objects.requireNonNull(referenceSolutions).toAbsolutePath().normalize();
    this.verifier = Objects.requireNonNull(verifier);
  }

  @Override
  public EvalTaskResult execute(EvalTask task) {
    var started = Instant.now();
    try (var workspace = FixtureWorkspace.copyOf(fixture)) {
      var index = new JavaProjectIndexer().index(workspace.root(), task.fixtureRevision());
      var relevant =
          task.relevantSymbols().stream()
              .flatMap(name -> index.named(name).stream())
              .map(value -> value.id())
              .collect(java.util.stream.Collectors.toSet());
      var retrieved =
          HybridRetriever.defaults()
              .retrieve(index, task.requirement(), Math.max(10, relevant.size()));
      var recall = relevant.isEmpty() ? 0 : RetrievalEvaluator.recallAtK(retrieved, relevant, 10);
      var overlay = referenceSolutions.resolve(task.id());
      if (Files.isDirectory(overlay)) {
        workspace.overlay(overlay);
      }
      var assertionOutcome = assertions.evaluate(workspace.root(), task.assertions());
      var verification = verifier.verify(workspace.root());
      var passed =
          assertionOutcome.successful() && verification.compiled() && verification.testsPassed();
      var artifacts =
          task.expectedFiles().stream()
              .filter(path -> Files.isRegularFile(workspace.root().resolve(path)))
              .toList();
      return new EvalTaskResult(
          task.id(),
          passed ? EvalTaskResult.Status.PASSED : EvalTaskResult.Status.FAILED,
          assertionOutcome.passed(),
          assertionOutcome.total(),
          verification.compiled(),
          verification.testsPassed(),
          recall,
          artifacts.size(),
          0,
          BigDecimal.ZERO,
          Duration.between(started, Instant.now()),
          artifacts,
          failure(assertionOutcome.failures(), verification.summary(), passed));
    }
  }

  private static String failure(
      List<String> assertionFailures, String verificationSummary, boolean passed) {
    if (passed) {
      return "";
    }
    var messages = new java.util.ArrayList<>(assertionFailures);
    if (!verificationSummary.isBlank()) {
      messages.add(verificationSummary);
    }
    return String.join("; ", messages);
  }
}

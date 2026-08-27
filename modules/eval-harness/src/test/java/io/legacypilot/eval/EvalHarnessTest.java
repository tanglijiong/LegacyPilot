package io.legacypilot.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvalHarnessTest {
  @TempDir Path directory;

  @Test
  void loadsTheVersionedFiveTaskDataset() {
    var dataset = repositoryRoot().resolve("evals/datasets/v0.1");
    var tasks = new EvalDatasetLoader().load(dataset);

    assertEquals(5, tasks.size());
    assertEquals("task-001", tasks.getFirst().id());
    assertEquals("task-005", tasks.getLast().id());
    assertTrue(tasks.getLast().requirement().contains("concurrent"));
    assertEquals("banking-fixture-v2", tasks.getLast().fixtureRevision());
  }

  @Test
  void isolatesOverlaysAndEvaluatesDeterministicAssertions() throws Exception {
    var fixture = directory.resolve("fixture");
    var overlay = directory.resolve("overlay");
    Files.createDirectories(fixture.resolve("src"));
    Files.createDirectories(overlay.resolve("src"));
    Files.writeString(fixture.resolve("src/value.txt"), "baseline");
    Files.writeString(overlay.resolve("src/value.txt"), "changed");
    Path isolated;
    try (var workspace = FixtureWorkspace.copyOf(fixture)) {
      isolated = workspace.root();
      workspace.overlay(overlay);
      var result =
          new SourceAssertionEngine()
              .evaluate(
                  isolated,
                  List.of(
                      new AssertionSpec("FILE_EXISTS", "src/value.txt", ""),
                      new AssertionSpec("CONTAINS", "src/value.txt", "changed"),
                      new AssertionSpec("NOT_CONTAINS", "src/value.txt", "baseline"),
                      new AssertionSpec("FILE_EXISTS", "../escape", "")));
      assertEquals(3, result.passed());
      assertFalse(result.successful());
      assertTrue(result.failures().getFirst().contains("escapes"));
      assertEquals("baseline", Files.readString(fixture.resolve("src/value.txt")));
    }
    assertFalse(Files.exists(isolated));
  }

  @Test
  void aggregatesConcurrentResultsAndSanitizesExecutorErrors() {
    var tasks = new EvalDatasetLoader().load(repositoryRoot().resolve("evals/datasets/v0.1"));
    var clock = Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC);
    var summary =
        new EvalRunner(clock)
            .run(
                "v0.1",
                "fake-model",
                "prompt-v1",
                "policy-v1",
                Map.of("java", "21"),
                tasks,
                3,
                task -> {
                  if (task.id().equals("task-005")) {
                    throw new IllegalStateException("secret provider detail");
                  }
                  return passed(task);
                });

    assertEquals(0.8, summary.successRate());
    assertEquals(0.8, summary.averageRecall());
    assertEquals(EvalTaskResult.Status.ERROR, summary.results().getLast().status());
    assertFalse(summary.results().getLast().failure().contains("secret"));
    var renderer = new EvalReportRenderer(new ObjectMapper().findAndRegisterModules());
    assertTrue(renderer.json(summary).contains("fake-model"));
    assertTrue(renderer.markdown(summary).contains("Success rate: 80%"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EvalRunner(clock)
                .run("v", "m", "p", "s", Map.of(), List.of(), 0, task -> passed(task)));
  }

  @Test
  void referenceOverlaysReachDeterministicCeiling() {
    var tasks = new EvalDatasetLoader().load(repositoryRoot().resolve("evals/datasets/v0.1"));
    FixtureVerifier verifier = workspace -> new FixtureVerifier.Verification(true, true, "ok");
    var executor =
        new ReferenceBaselineExecutor(
            repositoryRoot().resolve("samples/banking-demo"),
            repositoryRoot().resolve("evals/reference-solutions"),
            verifier);

    var results = tasks.stream().map(executor::execute).toList();

    assertTrue(
        results.stream().allMatch(result -> result.status() == EvalTaskResult.Status.PASSED));
    assertTrue(results.stream().allMatch(EvalTaskResult::compiled));
    assertTrue(results.stream().mapToInt(EvalTaskResult::passedAssertions).sum() >= 15);
  }

  private static EvalTaskResult passed(EvalTask task) {
    return new EvalTaskResult(
        task.id(),
        EvalTaskResult.Status.PASSED,
        task.assertions().size(),
        task.assertions().size(),
        true,
        true,
        1,
        3,
        100,
        new BigDecimal("0.01"),
        Duration.ofSeconds(1),
        task.expectedFiles(),
        "");
  }

  private static Path repositoryRoot() {
    return Path.of("../..").toAbsolutePath().normalize();
  }
}

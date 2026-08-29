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
  void loadsTheGovernedV03DraftWithVerifiedFixtureProvenance() {
    var dataset =
        new EvalDatasetLoader().loadVersioned(repositoryRoot().resolve("evals/datasets/v0.3"));

    assertEquals("eval-dataset-v2", dataset.schemaVersion());
    assertEquals("v0.3-draft.1", dataset.datasetVersion());
    assertEquals(5, dataset.tasks().size());
    assertEquals("banking-fixture-v2", dataset.tasks().getFirst().fixtureId());
    assertEquals("hard", dataset.tasks().getLast().difficulty());
    assertEquals(48_000, dataset.tasks().getLast().resourceBudget().maximumTokens());
    assertFalse(dataset.tasks().get(1).assertions().getFirst().value().isBlank());
    assertTrue(
        dataset.fixtures().get("banking-fixture-v2").path().endsWith("samples/banking-demo"));
  }

  @Test
  void failsClosedWhenDatasetOrFixtureBytesChange() throws Exception {
    var datasetTamper = createGovernedDataset(directory.resolve("dataset-tamper"));
    Files.writeString(
        datasetTamper.resolve("task-001/assertions.yml"),
        "- type: FILE_EXISTS\n  path: src/changed.txt\n");

    var datasetFailure =
        assertThrows(
            IllegalArgumentException.class,
            () -> new EvalDatasetLoader().loadVersioned(datasetTamper));
    assertTrue(datasetFailure.getMessage().contains("dataset checksum"));

    var fixtureTamper = createGovernedDataset(directory.resolve("fixture-tamper"));
    Files.writeString(directory.resolve("fixture-tamper/fixture/src/value.txt"), "tampered");

    var fixtureFailure =
        assertThrows(
            IllegalArgumentException.class,
            () -> new EvalDatasetLoader().loadVersioned(fixtureTamper));
    assertTrue(fixtureFailure.getMessage().contains("fixture checksum"));
  }

  @Test
  void rejectsCandidateChangesOutsideTheDeclaredProductionScope() throws Exception {
    var datasetPath = createGovernedDataset(directory.resolve("workspace-integrity"));
    var dataset = new EvalDatasetLoader().loadVersioned(datasetPath);
    var task = dataset.tasks().getFirst();
    var fixture = dataset.fixtures().get(task.fixtureId()).path();
    var guard = new WorkspaceIntegrityGuard();
    var baseline = guard.capture(fixture);

    Files.writeString(fixture.resolve("src/value.txt"), "changed\n");
    var allowed = guard.verify(baseline, fixture, task);
    assertTrue(allowed.successful());
    assertEquals(List.of("src/value.txt"), allowed.changedFiles());

    Files.writeString(fixture.resolve("pom.xml"), "tampered\n");
    var rejected = guard.verify(baseline, fixture, task);
    assertFalse(rejected.successful());
    assertTrue(rejected.violations().stream().anyMatch(value -> value.contains("pom.xml")));
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
                      new AssertionSpec("MATCHES_REGEX", "src/value.txt", "chan.*"),
                      new AssertionSpec("FILE_NOT_EXISTS", "src/missing.txt", ""),
                      new AssertionSpec("FILE_EXISTS", "../escape", "")));
      assertEquals(5, result.passed());
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

  private static Path createGovernedDataset(Path projectRoot) throws Exception {
    var dataset = projectRoot.resolve("evals/datasets/v0.3");
    var registry = projectRoot.resolve("evals/fixtures/fixture-one");
    var fixture = projectRoot.resolve("fixture");
    Files.createDirectories(dataset.resolve("task-001"));
    Files.createDirectories(registry);
    Files.createDirectories(fixture.resolve("src"));
    Files.writeString(projectRoot.resolve("pom.xml"), "<project/>\n");
    Files.writeString(fixture.resolve("src/value.txt"), "fixture\n");
    var fixtureDigest = EvalContentDigest.fixtureSha256(fixture);
    Files.writeString(
        registry.resolve("provenance.yml"),
        """
        schemaVersion: eval-fixture-v1
        id: fixture-one
        source: test:fixture
        revision: fixture-revision
        license: Apache-2.0
        sha256: %s
        path: ../../../fixture
        buildCommand:
          - test
        """
            .formatted(fixtureDigest));
    Files.writeString(
        dataset.resolve("task-001/task.yml"),
        """
        id: task-001
        category: validation
        difficulty: easy
        changeType: defect-fix
        expectedImpact: single-file
        requirement: Change the fixture value.
        fixtureId: fixture-one
        allowedFiles:
          - src/value.txt
        forbiddenFiles:
          - pom.xml
        expectedFiles:
          - src/value.txt
        relevantSymbols: []
        maximumSteps: 4
        timeoutSeconds: 30
        resourceBudget:
          maximumTokens: 1000
          maximumMemoryMb: 256
          maximumCostCents: 10
        """);
    Files.writeString(
        dataset.resolve("task-001/assertions.yml"),
        "- type: CONTAINS\n  path: src/value.txt\n  value: changed\n");
    var datasetDigest = EvalContentDigest.datasetSha256(dataset, List.of("task-001"));
    Files.writeString(
        dataset.resolve("manifest.yml"),
        """
        schemaVersion: eval-dataset-v2
        datasetVersion: v0.3-test
        datasetSha256: %s
        fixtureRegistry: ../../fixtures
        tasks:
          - task-001
        """
            .formatted(datasetDigest));
    return dataset;
  }

  private static Path repositoryRoot() {
    return Path.of("../..").toAbsolutePath().normalize();
  }
}

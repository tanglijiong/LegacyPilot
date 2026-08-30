package io.legacypilot.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexEvalTaskExecutorTest {
  @TempDir Path directory;

  @Test
  void runsInAnIsolatedWorkspaceAndRecordsStructuredUsage() throws Exception {
    var fixture = directory.resolve("fixture");
    Files.createDirectories(fixture.resolve("src"));
    Files.writeString(fixture.resolve("src/value.txt"), "baseline\n");
    var executable = directory.resolve("fake-codex");
    Files.writeString(
        executable,
        """
        #!/bin/sh
        cat >/dev/null
        while [ "$#" -gt 0 ]; do
          if [ "$1" = "--cd" ]; then shift; workspace="$1"; fi
          shift
        done
        printf 'changed\n' > "$workspace/src/value.txt"
        printf '%s\n' '{"type":"item.completed"}'
        printf '%s%s\n' \
          '{"type":"turn.completed","usage":{"input_tokens":100,' \
          '"cached_input_tokens":40,"output_tokens":10,"reasoning_output_tokens":2}}'
        """);
    assertTrue(executable.toFile().setExecutable(true));
    var task = task();
    var pricing =
        new EvalPricingSnapshot(
            "USD",
            "per-1m-tokens",
            new BigDecimal("1.00"),
            new BigDecimal("0.10"),
            new BigDecimal("5.00"),
            "test");
    var executor =
        new CodexEvalTaskExecutor(
            Map.of("fixture", fixture),
            directory.resolve("workspaces"),
            directory.resolve("references"),
            executable,
            "fake-model",
            "high",
            "Implement: {{requirement}}",
            pricing,
            workspace -> new FixtureVerifier.Verification(true, true, "ok"),
            new ObjectMapper().findAndRegisterModules());

    var result = executor.execute(task);

    assertEquals(EvalTaskResult.Status.PASSED, result.status());
    assertEquals(new EvalTokenUsage(100, 40, 10, 2), result.usage());
    assertEquals(new BigDecimal("0.000114"), result.estimatedCostUsd());
    assertEquals(1, result.steps());
    assertEquals("baseline\n", Files.readString(fixture.resolve("src/value.txt")));
    assertEquals(
        "changed\n", Files.readString(directory.resolve("workspaces/task-001/src/value.txt")));
  }

  private static EvalTask task() {
    return new EvalTask(
        "task-001",
        "test",
        "easy",
        "feature",
        "single-file",
        "change value",
        "fixture",
        "fixture-v1",
        List.of("src/value.txt"),
        List.of("pom.xml"),
        List.of("src/value.txt"),
        List.of(),
        4,
        30,
        new EvalTask.ResourceBudget(1000, 256, 10),
        List.of(new AssertionSpec("CONTAINS", "src/value.txt", "changed")));
  }
}

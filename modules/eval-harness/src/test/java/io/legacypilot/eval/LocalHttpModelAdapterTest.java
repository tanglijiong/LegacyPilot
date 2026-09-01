package io.legacypilot.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalHttpModelAdapterTest {
  @TempDir Path directory;

  @Test
  void runsAgentWithAnExplicitLoopbackEndpoint() throws Exception {
    var python = directory.resolve("python3");
    var environment = directory.resolve("environment.txt");
    Files.writeString(
        python,
        "#!/bin/sh\nprintf '%s' \"$LEGACY_PILOT_MODEL_BASE_URL\" > '"
            + environment
            + "'\ncat >/dev/null\nprintf '%s\\n' '{\"type\":\"turn.completed\",\"usage\":{}}'\n");
    assertTrue(python.toFile().setExecutable(true));
    var agent = directory.resolve("model_agent.py");
    Files.writeString(agent, "# test agent\n");
    var adapter =
        new LocalHttpModelAdapter(
            python,
            agent,
            "http://127.0.0.1:12434/engines/v1",
            "deepseek-local",
            "high",
            new ObjectMapper());
    var workspace = directory.resolve("workspace");
    Files.createDirectories(workspace);

    var invocation = adapter.invoke(workspace, task(), "prompt");

    assertEquals(0, invocation.exitCode());
    assertEquals(NetworkBoundary.LOCAL_ONLY, adapter.networkBoundary());
    assertEquals("local-http", adapter.adapterId());
    assertEquals("http://127.0.0.1:12434/engines/v1", Files.readString(environment));
  }

  @Test
  void rejectsRemoteOrCredentialBearingEndpoints() throws Exception {
    var python = directory.resolve("python3");
    Files.writeString(python, "#!/bin/sh\n");
    assertTrue(python.toFile().setExecutable(true));
    var agent = directory.resolve("model_agent.py");
    Files.writeString(agent, "# test agent\n");

    for (var endpoint :
        java.util.List.of(
            "https://localhost:12434/v1",
            "http://example.com:12434/v1",
            "http://token@localhost:12434/v1")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new LocalHttpModelAdapter(
                  python, agent, endpoint, "deepseek-local", "high", new ObjectMapper()));
    }
  }

  private static EvalTask task() {
    var source = "src/main/java/example/Value.java";
    return new EvalTask(
        "task-001",
        "defect-fix",
        "easy",
        "modify",
        "correctness",
        "fix it",
        "fixture",
        "revision",
        java.util.List.of(source),
        java.util.List.of("pom.xml"),
        java.util.List.of(source),
        java.util.List.of(),
        2,
        10,
        new EvalTask.ResourceBudget(1000, 512, 0),
        java.util.List.of(new AssertionSpec("FILE_EXISTS", source, "")));
  }
}

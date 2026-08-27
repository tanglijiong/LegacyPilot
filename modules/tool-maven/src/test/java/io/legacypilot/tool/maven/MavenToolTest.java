package io.legacypilot.tool.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.sandbox.SandboxExecutor;
import io.legacypilot.sandbox.SandboxLimits;
import io.legacypilot.sandbox.SandboxRequest;
import io.legacypilot.sandbox.SandboxResult;
import io.legacypilot.sandbox.SandboxStatus;
import io.legacypilot.tool.spi.ToolContext;
import io.legacypilot.tool.spi.ToolErrorCode;
import io.legacypilot.tool.spi.ToolFailureException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenToolTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  @TempDir Path workspace;

  @Test
  void createsFixedOfflineCommandsWithAllowlistedParameters() throws Exception {
    var sandbox = new FakeSandbox(SandboxStatus.SUCCESS);
    var tool = MavenTool.testClass(sandbox, workspace, Set.of("ci"), Set.of("skipITs"), limits());
    var input =
        MAPPER.readTree(
            """
        {"testClass":"io.example.SafeTest","profiles":["ci"],"properties":{"skipITs":"true"}}
        """);
    var command = tool.command(input);
    assertEquals("mvn", command.getFirst());
    assertTrue(
        command.containsAll(
            java.util.List.of(
                "--offline",
                "-Dmaven.repo.local=/maven-cache",
                "-Pci",
                "-DskipITs=true",
                "-Dtest=io.example.SafeTest",
                "test")));
    var result = tool.execute(new ToolContext("run", workspace, Set.of(), true), input);
    assertEquals("SUCCESS", result.path("status").asText());
    assertEquals(command, sandbox.last.command());
    assertEquals(0, result.path("exitCode").asInt());
  }

  @Test
  void exposesAllFixedOperationsAndRejectsInjectionOrUnlistedOptions() throws Exception {
    var sandbox = new FakeSandbox(SandboxStatus.SUCCESS);
    assertEquals(
        "compile_project",
        MavenTool.compile(sandbox, null, Set.of(), Set.of(), limits()).descriptor().name());
    assertEquals(
        "run_tests",
        MavenTool.tests(sandbox, null, Set.of(), Set.of(), limits()).descriptor().name());
    assertEquals(
        "static_analysis",
        MavenTool.staticAnalysis(sandbox, null, Set.of(), Set.of(), limits()).descriptor().name());
    var tool = MavenTool.testClass(sandbox, null, Set.of("ci"), Set.of("safe"), limits());
    assertThrows(
        ToolFailureException.class,
        () -> tool.command(MAPPER.readTree("{\"testClass\":\"SafeTest;touch /tmp/x\"}")));
    assertThrows(
        ToolFailureException.class,
        () ->
            tool.command(MAPPER.readTree("{\"testClass\":\"SafeTest\",\"profiles\":[\"prod\"]}")));
    var error =
        assertThrows(
            ToolFailureException.class,
            () ->
                tool.command(
                    MAPPER.readTree(
                        "{\"testClass\":\"SafeTest\",\"properties\":{\"unsafe\":\"true\"}}")));
    assertEquals(ToolErrorCode.INVALID_INPUT_SCHEMA, error.code());
  }

  @Test
  void mapsSandboxFailuresToStructuredToolFailures() throws Exception {
    var input = MAPPER.readTree("{}");
    var context = new ToolContext("run", workspace, Set.of(), true);
    var timeout =
        MavenTool.compile(
            new FakeSandbox(SandboxStatus.TIMED_OUT), null, Set.of(), Set.of(), limits());
    assertEquals(
        ToolErrorCode.TIMEOUT,
        assertThrows(ToolFailureException.class, () -> timeout.execute(context, input)).code());
    var failed =
        MavenTool.compile(
            new FakeSandbox(SandboxStatus.RESOURCE_LIMIT), null, Set.of(), Set.of(), limits());
    assertEquals(
        ToolErrorCode.COMMAND_FAILED,
        assertThrows(ToolFailureException.class, () -> failed.execute(context, input)).code());
  }

  private static SandboxLimits limits() {
    return new SandboxLimits(
        1, 64L * 1024 * 1024, 16, 1024 * 1024, 1024 * 1024, Duration.ofSeconds(2), 4096);
  }

  private static final class FakeSandbox implements SandboxExecutor {
    private final SandboxStatus status;
    private SandboxRequest last;

    private FakeSandbox(SandboxStatus status) {
      this.status = status;
    }

    @Override
    public SandboxResult execute(SandboxRequest request) {
      last = request;
      var exit = status == SandboxStatus.SUCCESS ? 0 : null;
      return new SandboxResult(
          request.executionId(), status, exit, "sandbox output", Duration.ofMillis(12), false);
    }

    @Override
    public boolean cancel(String executionId) {
      return false;
    }

    @Override
    public boolean available() {
      return true;
    }
  }
}

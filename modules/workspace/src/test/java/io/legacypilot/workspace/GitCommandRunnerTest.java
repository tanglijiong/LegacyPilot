package io.legacypilot.workspace;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitCommandRunnerTest {

  @Test
  void validatesConfigurationAndArguments() {
    assertThrows(IllegalArgumentException.class, () -> new GitCommandRunner(Duration.ZERO, 1024));
    assertThrows(
        IllegalArgumentException.class, () -> new GitCommandRunner(Duration.ofSeconds(1), 10));
    var runner = new GitCommandRunner(Duration.ofSeconds(1), 1024);
    assertThrows(IllegalArgumentException.class, () -> runner.run(null, List.of("bad\0value")));
  }

  @Test
  void reportsFailuresWithoutEchoingAllArguments() {
    var runner = new GitCommandRunner(Duration.ofSeconds(2), 1024);
    var failure =
        assertThrows(
            WorkspaceException.class,
            () -> runner.run(null, List.of("not-a-command", "secret-value")));
    assertTrue(failure.getMessage().contains("git not-a-command"));
    assertTrue(!failure.getMessage().contains("secret-value"));
    assertThrows(WorkspaceException.class, () -> runner.run(null, List.of()));
  }

  @Test
  void capsOutputAndTerminatesTimedOutCommands() {
    var runner = new GitCommandRunner(Duration.ofSeconds(2), 1024);
    var output = runner.run(null, List.of("-c", "alias.big=!yes x | head -c 2000", "big"));
    assertTrue(output.endsWith("[output truncated]"));

    var impatient = new GitCommandRunner(Duration.ofMillis(50), 1024);
    assertThrows(
        WorkspaceException.class,
        () -> impatient.run(null, List.of("-c", "alias.wait=!sleep 2", "wait")));
  }
}

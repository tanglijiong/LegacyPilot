package io.legacypilot.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliProcessIT {

  @TempDir Path temporaryDirectory;

  @Test
  void packagedCliDisplaysRealCommandHelp() throws IOException, InterruptedException {
    var java = Path.of(System.getProperty("java.home"), "bin", "java");
    var jar = Path.of("target", "legacy-pilot-cli-0.1.0-SNAPSHOT.jar").toAbsolutePath().normalize();
    var builder = new ProcessBuilder(java.toString(), "-jar", jar.toString(), "--help");
    builder
        .environment()
        .put("LEGACY_PILOT_DATA_DIR", temporaryDirectory.resolve("data").toString());
    builder
        .environment()
        .put("LEGACY_PILOT_WORK_ROOT", temporaryDirectory.resolve("work").toString());
    var process = builder.start();
    var completed = process.waitFor(Duration.ofSeconds(20).toMillis(), TimeUnit.MILLISECONDS);
    if (!completed) {
      process.destroyForcibly();
    }
    var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    var error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

    assertTrue(completed, "CLI did not finish before timeout");
    assertEquals(0, process.exitValue(), error);
    assertTrue(output.contains("project-register"));
    assertTrue(output.contains("run-start"));
  }
}

package io.legacypilot.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MavenFixtureVerifier implements FixtureVerifier {
  private final Path mavenWrapper;
  private final Duration timeout;

  public MavenFixtureVerifier(Path mavenWrapper, Duration timeout) {
    this.mavenWrapper = Objects.requireNonNull(mavenWrapper).toAbsolutePath().normalize();
    this.timeout = Objects.requireNonNull(timeout);
    if (!java.nio.file.Files.isRegularFile(this.mavenWrapper)
        || timeout.isZero()
        || timeout.isNegative()) {
      throw new IllegalArgumentException("Maven fixture verifier configuration is invalid");
    }
  }

  @Override
  public Verification verify(Path workspace) {
    var command =
        List.of(
            mavenWrapper.toString(),
            "--batch-mode",
            "--no-transfer-progress",
            "--offline",
            "-q",
            "-f",
            workspace.resolve("pom.xml").toString(),
            "test");
    try {
      var builder =
          new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true);
      JsonlProcessModelAdapter.scrubSensitiveEnvironment(builder.environment());
      var process = builder.start();
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var output =
            executor.submit(
                () -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
          process.destroyForcibly();
          return new Verification(false, false, "fixture verification timed out");
        }
        var text = bounded(output.get());
        var successful = process.exitValue() == 0;
        return new Verification(successful, successful, text);
      }
    } catch (IOException exception) {
      return new Verification(false, false, "unable to start fixture verification");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return new Verification(false, false, "fixture verification interrupted");
    } catch (java.util.concurrent.ExecutionException exception) {
      return new Verification(false, false, "unable to read fixture verification output");
    }
  }

  private static String bounded(String output) {
    return output.length() <= 4_000 ? output : output.substring(0, 4_000) + "…[TRUNCATED]";
  }
}

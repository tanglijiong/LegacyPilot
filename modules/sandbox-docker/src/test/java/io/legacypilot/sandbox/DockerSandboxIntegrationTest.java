package io.legacypilot.sandbox;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DockerSandboxIntegrationTest {

  @Test
  void runsMavenInARealDockerContainerWhenDaemonAndImageAreAvailable() throws Exception {
    var sandbox = DockerSandbox.secureMavenDefaults();
    assumeTrue(sandbox.available(), "Docker daemon is not available");
    var inspect =
        new ProcessBuilder("docker", "image", "inspect", DockerSandbox.DEFAULT_MAVEN_IMAGE).start();
    assumeTrue(inspect.waitFor() == 0, "Pinned Maven image is not present locally");
    var request =
        new SandboxRequest(
            "integration",
            DockerSandbox.DEFAULT_MAVEN_IMAGE,
            Path.of("."),
            null,
            List.of("mvn", "--version"),
            Map.of(),
            SandboxLimits.safeDefaults());
    assertTrue(sandbox.execute(request).successful());
  }
}

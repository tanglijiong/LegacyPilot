package io.legacypilot.sandbox;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerSandboxIntegrationTest {

  private static final String MAVEN_IMAGE =
      System.getenv().getOrDefault("LEGACY_PILOT_MAVEN_IMAGE", DockerSandbox.DEFAULT_MAVEN_IMAGE);

  @TempDir Path temporary;

  @Test
  void runsMavenInARealDockerContainerWhenDaemonAndImageAreAvailable() throws Exception {
    var sandbox = new DockerSandbox(Set.of(MAVEN_IMAGE), Set.of("mvn"), Set.of());
    assumeTrue(sandbox.available(), "Docker daemon is not available");
    var inspect = new ProcessBuilder("docker", "image", "inspect", MAVEN_IMAGE).start();
    assumeTrue(inspect.waitFor() == 0, "Pinned Maven image is not present locally");
    var request =
        new SandboxRequest(
            "integration",
            MAVEN_IMAGE,
            Path.of("."),
            null,
            List.of("mvn", "--version"),
            Map.of(),
            SandboxLimits.safeDefaults());
    assertTrue(sandbox.execute(request).successful());
  }

  @Test
  void prewarmsDependenciesThenRunsBankingTestsOfflineWhenDockerIsAvailable() throws Exception {
    var probe = new DockerSandbox(Set.of(MAVEN_IMAGE), Set.of("mvn"), Set.of());
    assumeTrue(probe.available(), "Docker daemon is not available");
    var inspect = new ProcessBuilder("docker", "image", "inspect", MAVEN_IMAGE).start();
    assumeTrue(inspect.waitFor() == 0, "Pinned Maven image is not present locally");
    var sandbox =
        new DockerSandbox(
            "docker",
            DockerImagePolicy.allowlisted(Set.of(MAVEN_IMAGE)),
            Set.of("mvn"),
            Set.of(),
            true);
    var fixture = Path.of("../..", "samples", "banking-demo").toAbsolutePath().normalize();
    var caches =
        new DependencyCacheManager(
            Files.createDirectory(temporary.resolve("caches")), 2L * 1024 * 1024 * 1024);
    var provisioned =
        new MavenDependencyProvisioner(sandbox, caches, MAVEN_IMAGE)
            .prewarm(fixture, SandboxLimits.safeDefaults());
    assertTrue(provisioned.result().successful(), provisioned.result().output());
    var offline =
        sandbox.execute(
            new SandboxRequest(
                "banking-offline",
                MAVEN_IMAGE,
                fixture,
                provisioned.path(),
                List.of(
                    "mvn", "--offline", "--batch-mode", "-Dmaven.repo.local=/maven-cache", "test"),
                Map.of(),
                SandboxLimits.safeDefaults()));
    assertTrue(offline.successful(), offline.output());
  }
}

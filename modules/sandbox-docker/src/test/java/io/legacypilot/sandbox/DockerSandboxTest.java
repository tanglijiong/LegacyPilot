package io.legacypilot.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerSandboxTest {

  @TempDir Path temporary;

  @Test
  void buildsARestrictedAllowlistedDockerCommand() throws Exception {
    var workspace = Files.createDirectory(temporary.resolve("workspace"));
    var cache = Files.createDirectory(temporary.resolve("cache"));
    var factory =
        new DockerCommandFactory(
            "docker-test", Set.of("image:1"), Set.of("mvn"), Set.of("MAVEN_OPTS"));
    var command =
        factory.create(
            "container",
            request(
                "id",
                workspace,
                cache,
                List.of("mvn", "test"),
                Map.of("MAVEN_OPTS", "-Xmx128m"),
                limits(Duration.ofSeconds(1), 4096, 10_000_000)));

    assertEquals("docker-test", command.getFirst());
    assertTrue(
        command.containsAll(
            List.of(
                "--network",
                "none",
                "--read-only",
                "--cap-drop",
                "ALL",
                "no-new-privileges",
                "--memory-swap",
                "--user",
                "1000:1000")));
    assertTrue(command.stream().anyMatch(value -> value.contains("dst=/workspace,rw")));
    assertTrue(command.stream().anyMatch(value -> value.contains("dst=/maven-cache,readonly")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            factory.create(
                "c",
                request(
                    "id2",
                    workspace,
                    null,
                    List.of("sh"),
                    Map.of(),
                    limits(Duration.ofSeconds(1), 4096, 10_000_000))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DockerCommandFactory(Set.of("other"), Set.of("mvn"), Set.of())
                .create(
                    "c",
                    request(
                        "id3",
                        workspace,
                        null,
                        List.of("mvn"),
                        Map.of(),
                        limits(Duration.ofSeconds(1), 4096, 10_000_000))));
  }

  @Test
  void executesReportsFailuresTruncatesAndChecksWorkspaceBudget() throws Exception {
    var workspace = Files.createDirectory(temporary.resolve("workspace"));
    var sandbox = sandbox(fakeDocker(true));

    var success =
        sandbox.execute(
            request(
                "success",
                workspace,
                null,
                List.of("mvn", "ok"),
                Map.of(),
                limits(Duration.ofSeconds(2), 4096, 10_000_000)));
    assertTrue(success.successful());
    assertEquals(0, success.exitCode());
    var failed =
        sandbox.execute(
            request(
                "failed",
                workspace,
                null,
                List.of("mvn", "fail"),
                Map.of(),
                limits(Duration.ofSeconds(2), 4096, 10_000_000)));
    assertEquals(SandboxStatus.FAILED, failed.status());
    assertEquals(7, failed.exitCode());
    var huge =
        sandbox.execute(
            request(
                "huge",
                workspace,
                null,
                List.of("mvn", "huge"),
                Map.of(),
                limits(Duration.ofSeconds(2), 1024, 10_000_000)));
    assertTrue(huge.outputTruncated());
    Files.writeString(workspace.resolve("large.bin"), "x".repeat(1024 * 1024 + 1));
    assertEquals(
        SandboxStatus.RESOURCE_LIMIT,
        sandbox
            .execute(
                request(
                    "large",
                    workspace,
                    null,
                    List.of("mvn"),
                    Map.of(),
                    limits(Duration.ofSeconds(1), 4096, 1024 * 1024)))
            .status());
  }

  @Test
  void handlesUnavailableTimeoutCancellationAndDuplicateIds() throws Exception {
    var workspace = Files.createDirectory(temporary.resolve("workspace"));
    var unavailable = sandbox(fakeDocker(false));
    assertFalse(unavailable.available());
    assertEquals(
        SandboxStatus.UNAVAILABLE,
        unavailable
            .execute(
                request(
                    "off",
                    workspace,
                    null,
                    List.of("mvn"),
                    Map.of(),
                    limits(Duration.ofSeconds(1), 4096, 10_000_000)))
            .status());
    assertFalse(unavailable.cancel("missing"));

    var sandbox = sandbox(fakeDocker(true));
    var timedOut =
        sandbox.execute(
            request(
                "timeout",
                workspace,
                null,
                List.of("mvn", "sleep"),
                Map.of(),
                limits(Duration.ofMillis(100), 4096, 10_000_000)));
    assertEquals(SandboxStatus.TIMED_OUT, timedOut.status());

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () ->
                  sandbox.execute(
                      request(
                          "same",
                          workspace,
                          null,
                          List.of("mvn", "sleep"),
                          Map.of(),
                          limits(Duration.ofSeconds(3), 4096, 10_000_000))));
      Thread.sleep(100);
      var duplicate =
          sandbox.execute(
              request(
                  "same",
                  workspace,
                  null,
                  List.of("mvn", "ok"),
                  Map.of(),
                  limits(Duration.ofSeconds(1), 4096, 10_000_000)));
      assertEquals(SandboxStatus.FAILED, duplicate.status());
      assertTrue(sandbox.cancel("same"));
      assertEquals(SandboxStatus.CANCELLED, first.get().status());
    }
  }

  @Test
  void validatesRequestAndLimitRecords() {
    assertThrows(IllegalArgumentException.class, () -> limits(Duration.ZERO, 1024, 10_000_000));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            request(
                "bad id!",
                temporary,
                null,
                List.of("mvn"),
                Map.of(),
                limits(Duration.ofSeconds(1), 4096, 10_000_000)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            request(
                "ok",
                temporary,
                null,
                List.of(),
                Map.of(),
                limits(Duration.ofSeconds(1), 4096, 10_000_000)));
  }

  @Test
  void separatesNetworkedPrewarmFromOfflineReadonlyExecutionAndRequiresPinnedImages()
      throws Exception {
    var workspace = Files.createDirectory(temporary.resolve("governed-workspace"));
    var cache = Files.createDirectory(temporary.resolve("governed-cache"));
    var pinned = "registry.example/maven@sha256:" + "a".repeat(64);
    var factory =
        new DockerCommandFactory(
            "docker-test",
            DockerImagePolicy.digestPinned(Set.of(pinned)),
            Set.of("mvn"),
            Set.of(),
            true);
    var prewarm =
        factory.create(
            "prewarm",
            new SandboxRequest(
                "prewarm",
                pinned,
                workspace,
                cache,
                List.of("mvn", "dependency:go-offline"),
                Map.of(),
                limits(Duration.ofSeconds(1), 4096, 10_000_000),
                SandboxPhase.DEPENDENCY_PREWARM));
    assertEquals("bridge", prewarm.get(prewarm.indexOf("--network") + 1));
    assertTrue(prewarm.stream().anyMatch(value -> value.endsWith("dst=/maven-cache,rw")));

    var offline =
        factory.create(
            "offline",
            new SandboxRequest(
                "offline",
                pinned,
                workspace,
                cache,
                List.of("mvn", "test"),
                Map.of(),
                limits(Duration.ofSeconds(1), 4096, 10_000_000)));
    assertEquals("none", offline.get(offline.indexOf("--network") + 1));
    assertTrue(offline.stream().anyMatch(value -> value.endsWith("dst=/maven-cache,readonly")));
    assertFalse(offline.contains("--privileged"));
    assertFalse(offline.contains("host"));
    assertThrows(
        IllegalArgumentException.class,
        () -> DockerImagePolicy.digestPinned(Set.of("maven:latest")));
  }

  @Test
  void createsContentAddressedCachesRejectsLinksAndEnforcesSize() throws Exception {
    var workspace = Files.createDirectory(temporary.resolve("cache-workspace"));
    Files.writeString(workspace.resolve("pom.xml"), "<project>one</project>");
    var root = temporary.resolve("managed-caches");
    var manager = new DependencyCacheManager(root, 1024 * 1024);
    var first = manager.cacheFor(workspace);
    assertEquals(first, manager.cacheFor(workspace));
    Files.writeString(workspace.resolve("pom.xml"), "<project>two</project>");
    var second = manager.cacheFor(workspace);
    assertFalse(first.equals(second));
    Files.writeString(second.resolve("large.bin"), "x".repeat(1024 * 1024 + 1));
    assertThrows(IllegalArgumentException.class, () -> manager.validate(second));

    var linked = root.resolve("linked");
    try {
      Files.createSymbolicLink(linked, first);
      assertThrows(IllegalArgumentException.class, () -> manager.validate(linked));
    } catch (UnsupportedOperationException exception) {
      // The remaining cache boundary checks still apply on file systems without symlinks.
    }
  }

  @Test
  void redactsCredentialShapedDockerOutput() throws Exception {
    var workspace = Files.createDirectory(temporary.resolve("redaction-workspace"));
    var sandbox = sandbox(fakeDocker(true));
    var result =
        sandbox.execute(
            request(
                "redact",
                workspace,
                null,
                List.of("mvn", "credential"),
                Map.of(),
                limits(Duration.ofSeconds(2), 4096, 10_000_000)));
    assertTrue(result.output().contains("api_key=[REDACTED]"));
    assertFalse(result.output().contains("plain-secret"));
  }

  @Test
  void provisionsContentAddressedMavenCacheThroughExplicitPrewarmPhase() throws Exception {
    var workspace = Files.createDirectory(temporary.resolve("prewarm-workspace"));
    Files.writeString(workspace.resolve("pom.xml"), "<project/>");
    var captured = new AtomicReference<SandboxRequest>();
    SandboxExecutor executor =
        new SandboxExecutor() {
          @Override
          public SandboxResult execute(SandboxRequest request) {
            captured.set(request);
            return new SandboxResult(
                request.executionId(), SandboxStatus.SUCCESS, 0, "ready", Duration.ZERO, false);
          }

          @Override
          public boolean cancel(String executionId) {
            return false;
          }

          @Override
          public boolean available() {
            return true;
          }
        };
    var provisioned =
        new MavenDependencyProvisioner(
                executor,
                new DependencyCacheManager(temporary.resolve("prewarm-caches"), 10_000_000),
                "image:1")
            .prewarm(workspace, limits(Duration.ofSeconds(1), 4096, 10_000_000));
    assertTrue(provisioned.result().successful());
    assertEquals(provisioned.path(), captured.get().dependencyCache());
    assertEquals(SandboxPhase.DEPENDENCY_PREWARM, captured.get().phase());
    assertTrue(captured.get().command().contains("dependency:go-offline"));
  }

  private DockerSandbox sandbox(Path executable) {
    return new DockerSandbox(executable.toString(), Set.of("image:1"), Set.of("mvn"), Set.of());
  }

  private Path fakeDocker(boolean available) throws Exception {
    var script = temporary.resolve("docker-" + available + ".sh");
    Files.writeString(
        script,
        """
        #!/bin/sh
        if [ "$1" = "info" ]; then exit %s; fi
        if [ "$1" = "rm" ]; then exit 0; fi
        for value in "$@"; do
          if [ "$value" = "sleep" ]; then sleep 2; echo slept; exit 0; fi
          if [ "$value" = "fail" ]; then echo failed; exit 7; fi
          if [ "$value" = "huge" ]; then
            i=0; while [ $i -lt 3000 ]; do printf x; i=$((i + 1)); done; exit 0
          fi
          if [ "$value" = "credential" ]; then echo api_key=plain-secret; exit 0; fi
        done
        echo success
        exit 0
        """
            .formatted(available ? 0 : 1));
    assertTrue(script.toFile().setExecutable(true));
    return script;
  }

  private static SandboxRequest request(
      String id,
      Path workspace,
      Path cache,
      List<String> command,
      Map<String, String> environment,
      SandboxLimits limits) {
    return new SandboxRequest(id, "image:1", workspace, cache, command, environment, limits);
  }

  private static SandboxLimits limits(Duration timeout, int output, long workspace) {
    return new SandboxLimits(1, 64L * 1024 * 1024, 16, 1024 * 1024, workspace, timeout, output);
  }
}

package io.legacypilot.sandbox;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MavenDependencyProvisioner {
  private final SandboxExecutor sandbox;
  private final DependencyCacheManager caches;
  private final String image;

  public MavenDependencyProvisioner(
      SandboxExecutor sandbox, DependencyCacheManager caches, String image) {
    this.sandbox = Objects.requireNonNull(sandbox);
    this.caches = Objects.requireNonNull(caches);
    this.image = Objects.requireNonNull(image);
  }

  public ProvisionedCache prewarm(Path workspace, SandboxLimits limits) {
    var cache = caches.cacheFor(workspace);
    var result =
        sandbox.execute(
            new SandboxRequest(
                "prewarm-"
                    + Objects.requireNonNull(cache.getFileName()).toString().substring(0, 16),
                image,
                workspace,
                cache,
                List.of(
                    "mvn",
                    "--batch-mode",
                    "-Dmaven.repo.local=/maven-cache",
                    "-Dgroups=__LegacyPilotDependencyPrewarm__",
                    "dependency:go-offline",
                    "test"),
                Map.of(),
                limits,
                SandboxPhase.DEPENDENCY_PREWARM));
    caches.validate(cache);
    return new ProvisionedCache(cache, result);
  }

  public record ProvisionedCache(Path path, SandboxResult result) {}
}

package io.legacypilot.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public record AirGappedContainerConfig(
    Path modelWeights,
    Path modelSocketDirectory,
    String modelArtifactSha256,
    String memory,
    int cpus,
    int pids,
    String gpuDevices,
    int tensorParallelSize,
    int maxModelLength) {
  public AirGappedContainerConfig {
    modelWeights = Objects.requireNonNull(modelWeights).toAbsolutePath().normalize();
    modelSocketDirectory =
        Objects.requireNonNull(modelSocketDirectory).toAbsolutePath().normalize();
    Objects.requireNonNull(modelArtifactSha256);
    Objects.requireNonNull(memory);
    Objects.requireNonNull(gpuDevices);
    if (!Files.isDirectory(modelWeights)
        || unsafeMount(modelWeights)
        || !Files.isDirectory(modelSocketDirectory)
        || unsafeMount(modelSocketDirectory)
        || !modelArtifactSha256.matches("[0-9a-f]{64}")
        || !memory.matches("[1-9][0-9]*(?:[kKmMgG])")
        || cpus < 1
        || cpus > 256
        || pids < 64
        || pids > 32_768
        || !gpuDevices.matches("none|all|[0-9]+(?:,[0-9]+)*")
        || tensorParallelSize < 1
        || tensorParallelSize > 64
        || maxModelLength < 4096
        || maxModelLength > 131_072) {
      throw new IllegalArgumentException("air-gapped container resources are invalid");
    }
  }

  private static boolean unsafeMount(Path path) {
    var value = path.toString();
    return value.indexOf(',') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
  }
}

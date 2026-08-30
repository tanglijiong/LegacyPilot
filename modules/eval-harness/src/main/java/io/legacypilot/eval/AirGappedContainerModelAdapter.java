package io.legacypilot.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Runs an on-premise model agent in a pinned container with Docker networking disabled. */
public final class AirGappedContainerModelAdapter implements EvalModelAdapter {
  private static final String PINNED_IMAGE = "[A-Za-z0-9./_-]+@sha256:[0-9a-f]{64}";
  private static final String SAFE_COMMAND = "/[A-Za-z0-9._/-]+";

  private final EvalModelAdapter delegate;

  public AirGappedContainerModelAdapter(
      Path dockerExecutable,
      String image,
      String agentCommand,
      String model,
      String reasoningEffort,
      AirGappedContainerConfig config,
      ObjectMapper mapper) {
    var docker = Objects.requireNonNull(dockerExecutable).toAbsolutePath().normalize();
    if (!Files.isRegularFile(docker)
        || !Files.isExecutable(docker)
        || image == null
        || !image.matches(PINNED_IMAGE)
        || agentCommand == null
        || !agentCommand.matches(SAFE_COMMAND)
        || model == null
        || model.isBlank()
        || config == null
        || reasoningEffort == null
        || !reasoningEffort.matches("low|medium|high|xhigh|max|ultra")) {
      throw new IllegalArgumentException("air-gapped model adapter configuration is invalid");
    }
    validateServiceManifest(config, image, model, mapper);
    delegate =
        new JsonlProcessModelAdapter(
            "airgap-container",
            NetworkBoundary.AIR_GAPPED,
            (workspace, task) ->
                command(docker, image, agentCommand, model, reasoningEffort, config, workspace),
            mapper);
  }

  private static void validateServiceManifest(
      AirGappedContainerConfig config, String image, String model, ObjectMapper mapper) {
    var path = config.modelSocketDirectory().resolve("service-manifest.json");
    try {
      var manifest = mapper.readTree(path.toFile());
      if (!manifest.path("image").asText().equals(image)
          || !manifest.path("model").asText().equals(model)
          || !manifest.path("modelArtifactSha256").asText().equals(config.modelArtifactSha256())
          || !manifest.path("memory").asText().equals(config.memory())
          || manifest.path("cpus").asInt(-1) != config.cpus()
          || manifest.path("pids").asInt(-1) != config.pids()
          || !manifest.path("gpus").asText().equals(config.gpuDevices())
          || manifest.path("tensorParallelSize").asInt(-1) != config.tensorParallelSize()
          || manifest.path("maxModelLength").asInt(-1) != config.maxModelLength()) {
        throw new IllegalArgumentException("model service attestation does not match the eval run");
      }
    } catch (IOException exception) {
      throw new IllegalArgumentException("model service attestation is unavailable", exception);
    }
  }

  @Override
  public EvalModelInvocation invoke(Path workspace, EvalTask task, String prompt) {
    return delegate.invoke(workspace, task, prompt);
  }

  @Override
  public String adapterId() {
    return delegate.adapterId();
  }

  @Override
  public NetworkBoundary networkBoundary() {
    return delegate.networkBoundary();
  }

  static List<String> command(
      Path docker,
      String image,
      String agentCommand,
      String model,
      String reasoningEffort,
      AirGappedContainerConfig config,
      Path workspace) {
    var normalized = workspace.toAbsolutePath().normalize();
    if (!Files.isDirectory(normalized)
        || normalized.toString().indexOf(',') >= 0
        || normalized.toString().indexOf('\n') >= 0
        || normalized.toString().indexOf('\r') >= 0) {
      throw new IllegalArgumentException("air-gapped workspace is invalid");
    }
    var command = new ArrayList<String>();
    command.addAll(
        List.of(
            docker.toString(),
            "run",
            "--rm",
            "--interactive",
            "--pull",
            "never",
            "--network",
            "none",
            "--read-only",
            "--cap-drop",
            "ALL",
            "--security-opt",
            "no-new-privileges",
            "--pids-limit",
            Integer.toString(config.pids()),
            "--memory",
            config.memory(),
            "--memory-swap",
            config.memory(),
            "--cpus",
            Integer.toString(config.cpus()),
            "--user",
            "1000:1000",
            "--workdir",
            "/workspace",
            "--tmpfs",
            "/tmp:rw,noexec,nosuid,nodev,size=1g",
            "--mount",
            "type=bind,src=" + normalized + ",dst=/workspace",
            "--mount",
            "type=bind,src="
                + config.modelSocketDirectory()
                + ",dst=/run/legacy-pilot-model,readonly",
            "--env",
            "HOME=/tmp/home",
            "--env",
            "LEGACY_PILOT_MODEL_SOCKET=/run/legacy-pilot-model/vllm.sock"));
    command.addAll(
        List.of(
            image,
            agentCommand,
            "--workspace",
            "/workspace",
            "--model",
            "/models/model",
            "--served-model-name",
            model,
            "--reasoning-effort",
            reasoningEffort,
            "--jsonl"));
    return List.copyOf(command);
  }
}

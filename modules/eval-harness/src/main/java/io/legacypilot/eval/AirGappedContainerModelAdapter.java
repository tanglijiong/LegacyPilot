package io.legacypilot.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        || reasoningEffort == null
        || !reasoningEffort.matches("low|medium|high|xhigh|max|ultra")) {
      throw new IllegalArgumentException("air-gapped model adapter configuration is invalid");
    }
    delegate =
        new JsonlProcessModelAdapter(
            "airgap-container",
            NetworkBoundary.AIR_GAPPED,
            (workspace, task) ->
                command(docker, image, agentCommand, model, reasoningEffort, workspace),
            mapper);
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
            "256",
            "--memory",
            "4g",
            "--memory-swap",
            "4g",
            "--cpus",
            "2",
            "--user",
            "1000:1000",
            "--workdir",
            "/workspace",
            "--tmpfs",
            "/tmp:rw,noexec,nosuid,nodev,size=1g",
            "--mount",
            "type=bind,src=" + normalized + ",dst=/workspace",
            "--env",
            "HOME=/tmp/home",
            image,
            agentCommand,
            "--workspace",
            "/workspace",
            "--model",
            model,
            "--reasoning-effort",
            reasoningEffort,
            "--jsonl"));
    return List.copyOf(command);
  }
}

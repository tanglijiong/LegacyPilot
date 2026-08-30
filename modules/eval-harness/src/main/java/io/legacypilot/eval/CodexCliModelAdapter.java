package io.legacypilot.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Explicit external-provider compatibility adapter for public, synthetic benchmarks only. */
public final class CodexCliModelAdapter implements EvalModelAdapter {
  private final EvalModelAdapter delegate;

  public CodexCliModelAdapter(
      Path executable, String model, String reasoningEffort, ObjectMapper mapper) {
    var normalized = Objects.requireNonNull(executable).toAbsolutePath().normalize();
    if (!Files.isRegularFile(normalized)
        || !Files.isExecutable(normalized)
        || model == null
        || model.isBlank()
        || reasoningEffort == null
        || !reasoningEffort.matches("low|medium|high|xhigh|max|ultra")) {
      throw new IllegalArgumentException("Codex adapter configuration is invalid");
    }
    delegate =
        new JsonlProcessModelAdapter(
            "codex-cli",
            NetworkBoundary.EXTERNAL_PROVIDER,
            (workspace, task) ->
                List.of(
                    normalized.toString(),
                    "exec",
                    "--ephemeral",
                    "--ignore-user-config",
                    "--ignore-rules",
                    "--skip-git-repo-check",
                    "--sandbox",
                    "workspace-write",
                    "--cd",
                    workspace.toString(),
                    "--json",
                    "--model",
                    model,
                    "--config",
                    "model_reasoning_effort=\"" + reasoningEffort + "\"",
                    "-"),
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
}

package io.legacypilot.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Runs the repository's model agent against an OpenAI-compatible loopback endpoint. */
public final class LocalHttpModelAdapter implements EvalModelAdapter {
  private final EvalModelAdapter delegate;

  public LocalHttpModelAdapter(
      Path python,
      Path agentScript,
      String baseUrl,
      String model,
      String reasoningEffort,
      ObjectMapper mapper) {
    var executable = Objects.requireNonNull(python).toAbsolutePath().normalize();
    var script = Objects.requireNonNull(agentScript).toAbsolutePath().normalize();
    var endpoint = validateBaseUrl(baseUrl);
    if (!Files.isRegularFile(executable)
        || !Files.isExecutable(executable)
        || !Files.isRegularFile(script)
        || model == null
        || model.isBlank()
        || reasoningEffort == null
        || !reasoningEffort.matches("low|medium|high|xhigh|max|ultra")) {
      throw new IllegalArgumentException("local HTTP model adapter configuration is invalid");
    }
    delegate =
        new JsonlProcessModelAdapter(
            "local-http",
            NetworkBoundary.LOCAL_ONLY,
            (workspace, task) ->
                command(executable, script, model, reasoningEffort, workspace, task),
            Map.of("LEGACY_PILOT_MODEL_BASE_URL", endpoint),
            mapper);
  }

  private static List<String> command(
      Path executable,
      Path script,
      String model,
      String reasoningEffort,
      Path workspace,
      EvalTask task) {
    var command =
        new ArrayList<>(
            List.of(
                executable.toString(),
                script.toString(),
                "--workspace",
                workspace.toAbsolutePath().normalize().toString(),
                "--model",
                model,
                "--served-model-name",
                model,
                "--reasoning-effort",
                reasoningEffort,
                "--jsonl"));
    task.allowedFiles()
        .forEach(
            path -> {
              command.add("--allowed-file");
              command.add(path);
            });
    task.assertions()
        .forEach(
            assertion -> {
              command.add("--acceptance");
              command.add(
                  assertion.type()
                      + " "
                      + assertion.path()
                      + (assertion.value().isBlank() ? "" : " => " + assertion.value()));
            });
    return List.copyOf(command);
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

  private static String validateBaseUrl(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("local model base URL must use loopback HTTP");
    }
    try {
      var uri = URI.create(value);
      var host = uri.getHost();
      if (!"http".equals(uri.getScheme())
          || host == null
          || !(host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1"))
          || uri.getPort() < 1
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null) {
        throw new IllegalArgumentException("local model base URL must use loopback HTTP");
      }
      return uri.toString().replaceAll("/+$", "");
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("local model base URL must use loopback HTTP", exception);
    }
  }
}

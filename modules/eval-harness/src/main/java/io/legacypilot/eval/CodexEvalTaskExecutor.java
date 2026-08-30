package io.legacypilot.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;

/**
 * Backward-compatible public benchmark executor. New deployments should compose {@link
 * GovernedEvalTaskExecutor} with an explicit model adapter.
 */
public final class CodexEvalTaskExecutor implements EvalTaskExecutor {
  private final GovernedEvalTaskExecutor delegate;

  public CodexEvalTaskExecutor(
      Map<String, Path> fixtures,
      Path workspaceRoot,
      Path referenceSolutions,
      Path executable,
      String model,
      String reasoningEffort,
      String promptTemplate,
      EvalPricingSnapshot pricing,
      FixtureVerifier verifier,
      ObjectMapper mapper) {
    delegate =
        new GovernedEvalTaskExecutor(
            fixtures,
            workspaceRoot,
            referenceSolutions,
            promptTemplate,
            pricing,
            verifier,
            new CodexCliModelAdapter(executable, model, reasoningEffort, mapper));
  }

  @Override
  public EvalTaskResult execute(EvalTask task) {
    return delegate.execute(task);
  }
}

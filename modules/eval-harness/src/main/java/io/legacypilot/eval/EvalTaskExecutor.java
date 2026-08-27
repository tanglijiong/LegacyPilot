package io.legacypilot.eval;

@FunctionalInterface
public interface EvalTaskExecutor {
  EvalTaskResult execute(EvalTask task);
}

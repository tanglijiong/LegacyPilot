package io.legacypilot.eval;

@FunctionalInterface
public interface EvalFaultInjector {
  EvalFaultInjector NONE = (point, taskId) -> {};

  void reach(Point point, String taskId);

  enum Point {
    BEFORE_TASK_START,
    AFTER_MODEL_RESPONSE,
    AFTER_PATCH_APPLIED,
    BEFORE_RESULT_PERSIST
  }
}

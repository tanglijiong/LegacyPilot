package io.legacypilot.eval;

import java.nio.file.Path;

/** Executes one model attempt without owning fixture preparation or verification. */
public interface EvalModelAdapter {
  EvalModelInvocation invoke(Path workspace, EvalTask task, String prompt);

  String adapterId();

  NetworkBoundary networkBoundary();
}

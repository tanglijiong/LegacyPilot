package io.legacypilot.eval;

import java.util.Locale;

public final class EvalFailureClassifier {
  private EvalFailureClassifier() {}

  public static EvalFailure classify(EvalTaskResult result) {
    if (result.status() == EvalTaskResult.Status.PASSED) {
      return EvalFailure.NONE;
    }
    var failure = result.failure().toLowerCase(Locale.ROOT);
    if (contains(failure, "budget", "cost limit", "token limit", "time limit")) {
      return EvalFailure.BUDGET_EXHAUSTED;
    }
    if (contains(failure, "provider", "rate limit", "model unavailable", "model response")) {
      return EvalFailure.PROVIDER_FAILURE;
    }
    if (contains(failure, "policy", "approval", "capability", "denied")) {
      return EvalFailure.POLICY_OR_APPROVAL_BLOCK;
    }
    if (contains(
        failure,
        "integrity violation",
        "fixture is unavailable",
        "dataset checksum",
        "fixture checksum")) {
      return EvalFailure.DATASET_OR_INFRA_FAILURE;
    }
    if (result.status() == EvalTaskResult.Status.ERROR
        || contains(failure, "sandbox", "agent process", "docker", "tool")) {
      return EvalFailure.TOOL_OR_SANDBOX_FAILURE;
    }
    if (contains(failure, "tests run:", "there are test failures", "<<< failure!", "<<< error!")
        || (result.compiled() && !result.testsPassed())) {
      return EvalFailure.TEST_FAILURE;
    }
    if (!result.compiled()
        || contains(failure, "compilation error", "compilation failure", "compiler failed")) {
      return EvalFailure.COMPILE_FAILURE;
    }
    if (result.passedAssertions() < result.totalAssertions()) {
      return EvalFailure.ASSERTION_FAILURE;
    }
    if (result.retrievalRecall() == 0) {
      return EvalFailure.RETRIEVAL_MISS;
    }
    if (contains(failure, "plan")) {
      return EvalFailure.PLAN_ERROR;
    }
    return EvalFailure.PATCH_ERROR;
  }

  private static boolean contains(String value, String... candidates) {
    for (var candidate : candidates) {
      if (value.contains(candidate)) {
        return true;
      }
    }
    return false;
  }
}

package io.legacypilot.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.legacypilot.application.error.ConcurrentUpdateException;
import io.legacypilot.application.error.ResourceNotFoundException;
import io.legacypilot.domain.run.InvalidStateTransitionException;
import io.legacypilot.domain.run.RunStatus;
import io.legacypilot.workspace.WorkspaceException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class ApiExceptionHandlerTest {
  private final ApiExceptionHandler handler = new ApiExceptionHandler();

  @Test
  void assignsStableCodesToApplicationFailures() {
    assertProblem(
        handler.notFound(new ResourceNotFoundException("run", "run-1")), 404, "RESOURCE_NOT_FOUND");
    assertProblem(
        handler.conflict(new ConcurrentUpdateException("run", "run-1")), 409, "CONCURRENT_UPDATE");
    assertProblem(
        handler.conflict(
            new InvalidStateTransitionException(RunStatus.CREATED, RunStatus.SUCCEEDED)),
        409,
        "INVALID_STATE_TRANSITION");
    assertProblem(
        handler.conflict(new WorkspaceException("workspace busy")), 409, "WORKSPACE_CONFLICT");
    assertProblem(
        handler.conflict(new IllegalStateException("execution busy")), 409, "EXECUTION_CONFLICT");
    assertProblem(
        handler.badRequest(new IllegalArgumentException("bad input")), 400, "INVALID_REQUEST");
  }

  @Test
  void addsTheCurrentCorrelationIdAndUsesASafeFallbackDetail() {
    var correlationId = "4f343447-c48e-4f41-b24e-3a35d573dd83";
    try (var ignored = MDC.putCloseable(CorrelationIdFilter.MDC_KEY, correlationId)) {
      var problem = handler.badRequest(new IllegalArgumentException((String) null));
      assertEquals("IllegalArgumentException", problem.getDetail());
      assertEquals(correlationId, problem.getProperties().get("correlationId"));
    }
    var problem = handler.badRequest(new IllegalArgumentException("bad input"));
    assertFalse(problem.getProperties().containsKey("correlationId"));
  }

  private static void assertProblem(
      org.springframework.http.ProblemDetail problem, int status, String code) {
    assertEquals(status, problem.getStatus());
    assertEquals(code, problem.getProperties().get("code"));
  }
}

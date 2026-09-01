package io.legacypilot.server.api;

import io.legacypilot.application.error.ConcurrentUpdateException;
import io.legacypilot.application.error.ResourceNotFoundException;
import io.legacypilot.domain.run.InvalidStateTransitionException;
import io.legacypilot.workspace.WorkspaceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(ResourceNotFoundException.class)
  ProblemDetail notFound(ResourceNotFoundException exception) {
    return problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception);
  }

  @ExceptionHandler({
    ConcurrentUpdateException.class,
    InvalidStateTransitionException.class,
    WorkspaceException.class,
    IllegalStateException.class
  })
  ProblemDetail conflict(RuntimeException exception) {
    return problem(HttpStatus.CONFLICT, conflictCode(exception), exception);
  }

  @ExceptionHandler({IllegalArgumentException.class, NullPointerException.class})
  ProblemDetail badRequest(RuntimeException exception) {
    return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception);
  }

  private static ProblemDetail problem(HttpStatus status, String code, RuntimeException exception) {
    return ApiProblems.create(status, code, safeMessage(exception));
  }

  private static String conflictCode(RuntimeException exception) {
    if (exception instanceof ConcurrentUpdateException) {
      return "CONCURRENT_UPDATE";
    }
    if (exception instanceof InvalidStateTransitionException) {
      return "INVALID_STATE_TRANSITION";
    }
    if (exception instanceof WorkspaceException) {
      return "WORKSPACE_CONFLICT";
    }
    return "EXECUTION_CONFLICT";
  }

  private static String safeMessage(RuntimeException exception) {
    return exception.getMessage() == null
        ? exception.getClass().getSimpleName()
        : exception.getMessage();
  }
}

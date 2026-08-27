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
    return problem(HttpStatus.NOT_FOUND, exception);
  }

  @ExceptionHandler({
    ConcurrentUpdateException.class,
    InvalidStateTransitionException.class,
    WorkspaceException.class
  })
  ProblemDetail conflict(RuntimeException exception) {
    return problem(HttpStatus.CONFLICT, exception);
  }

  @ExceptionHandler({IllegalArgumentException.class, NullPointerException.class})
  ProblemDetail badRequest(RuntimeException exception) {
    return problem(HttpStatus.BAD_REQUEST, exception);
  }

  private static ProblemDetail problem(HttpStatus status, RuntimeException exception) {
    var result = ProblemDetail.forStatusAndDetail(status, safeMessage(exception));
    result.setTitle(status.getReasonPhrase());
    return result;
  }

  private static String safeMessage(RuntimeException exception) {
    return exception.getMessage() == null
        ? exception.getClass().getSimpleName()
        : exception.getMessage();
  }
}

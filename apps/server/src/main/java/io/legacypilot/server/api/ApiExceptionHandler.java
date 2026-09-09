package io.legacypilot.server.api;

import io.legacypilot.application.error.ConcurrentUpdateException;
import io.legacypilot.application.error.ResourceNotFoundException;
import io.legacypilot.application.error.SourceAccessException;
import io.legacypilot.domain.run.InvalidStateTransitionException;
import io.legacypilot.server.service.SourceAccessAudit;
import io.legacypilot.workspace.WorkspaceException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiExceptionHandler {

  private static final String SOURCE_PREFIX = "/api/v1/projects/";
  private static final String SOURCE_SUFFIX = "/source";
  private final SourceAccessAudit sourceAudit;

  public ApiExceptionHandler(SourceAccessAudit sourceAudit) {
    this.sourceAudit = sourceAudit;
  }

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

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ProblemDetail uploadTooLarge(MaxUploadSizeExceededException exception) {
    return ApiProblems.create(
        HttpStatus.PAYLOAD_TOO_LARGE,
        "DIRECTORY_UPLOAD_TOO_LARGE",
        "所选目录超过接入上限：单个文件最多 64 MB，项目文件总计最多 256 MB。" + "请排除依赖、构建产物或改用后端可访问的本地路径。");
  }

  @ExceptionHandler(SourceAccessException.class)
  ResponseEntity<ProblemDetail> sourceAccess(
      SourceAccessException exception, HttpServletRequest request, Authentication authentication) {
    sourceAudit.record(
        "DENIED",
        authentication == null ? "anonymous" : authentication.getName(),
        sourceProjectId(request.getRequestURI()),
        request.getParameter("revision"),
        request.getParameter("path"),
        integerParameter(request, "startLine", 1),
        integerParameter(request, "endLine", 1),
        Boolean.parseBoolean(request.getParameter("fullFile")),
        exception.reason().name());
    var status = sourceStatus(exception.reason());
    return ResponseEntity.status(status)
        .body(
            ApiProblems.create(
                status, "SOURCE_" + exception.reason().name(), safeMessage(exception)));
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

  private static HttpStatus sourceStatus(SourceAccessException.Reason reason) {
    return switch (reason) {
      case INVALID_PATH -> HttpStatus.BAD_REQUEST;
      case REVISION_MISMATCH -> HttpStatus.CONFLICT;
      case FILE_NOT_FOUND -> HttpStatus.NOT_FOUND;
      case FILE_TOO_LARGE, RANGE_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
      case SYMBOLIC_LINK, BINARY_FILE -> HttpStatus.UNPROCESSABLE_ENTITY;
      case READ_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
    };
  }

  private static String sourceProjectId(String uri) {
    if (uri != null && uri.startsWith(SOURCE_PREFIX) && uri.endsWith(SOURCE_SUFFIX)) {
      return uri.substring(SOURCE_PREFIX.length(), uri.length() - SOURCE_SUFFIX.length());
    }
    return "-";
  }

  private static int integerParameter(HttpServletRequest request, String name, int fallback) {
    try {
      var value = request.getParameter(name);
      return value == null ? fallback : Integer.parseInt(value);
    } catch (NumberFormatException exception) {
      return fallback;
    }
  }
}

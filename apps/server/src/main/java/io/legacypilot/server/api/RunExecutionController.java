package io.legacypilot.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.application.error.ResourceNotFoundException;
import io.legacypilot.application.execution.RunExecutionCommand;
import io.legacypilot.observability.ReportStore;
import io.legacypilot.observability.RunReport;
import io.legacypilot.observability.TaskArtifact;
import io.legacypilot.runtime.AgentCheckpoint;
import io.legacypilot.runtime.ApprovalScope;
import io.legacypilot.runtime.ApprovalStore;
import io.legacypilot.runtime.CheckpointStore;
import io.legacypilot.runtime.RuntimeApproval;
import io.legacypilot.server.security.RunSecurityContextStore;
import io.legacypilot.server.service.ChangeReviewService;
import io.legacypilot.server.service.RunEventStreamService;
import io.legacypilot.server.service.RunExecutionDispatcher;
import io.legacypilot.server.service.RunRecoveryService;
import io.legacypilot.tool.spi.ActionDigests;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/runs")
public final class RunExecutionController {
  private final RunExecutionDispatcher dispatcher;
  private final CheckpointStore checkpoints;
  private final ReportStore reports;
  private final ApprovalStore approvals;
  private final RunSecurityContextStore securityContexts;
  private final Clock clock;
  private final ObjectMapper mapper;
  private final RunEventStreamService events;
  private final ChangeReviewService changeReviews;
  private final RunRecoveryService recovery;

  public RunExecutionController(
      RunExecutionDispatcher dispatcher,
      CheckpointStore checkpoints,
      ReportStore reports,
      ApprovalStore approvals,
      RunSecurityContextStore securityContexts,
      Clock clock,
      ObjectMapper mapper,
      RunEventStreamService events,
      ChangeReviewService changeReviews,
      RunRecoveryService recovery) {
    this.dispatcher = dispatcher;
    this.checkpoints = checkpoints;
    this.reports = reports;
    this.approvals = approvals;
    this.securityContexts = securityContexts;
    this.clock = clock;
    this.mapper = mapper;
    this.events = events;
    this.changeReviews = changeReviews;
    this.recovery = recovery;
  }

  @PostMapping("/{runId}/execute")
  ResponseEntity<ExecutionCommandView> execute(
      @PathVariable String runId,
      @org.springframework.web.bind.annotation.RequestHeader("Idempotency-Key")
          String idempotencyKey,
      @org.springframework.web.bind.annotation.RequestHeader("If-Match") long expectedVersion,
      Authentication authentication) {
    return accepted(
        dispatcher.accept(
            runId,
            RunExecutionCommand.Operation.EXECUTE,
            idempotencyKey,
            authentication.getName(),
            MDC.get(CorrelationIdFilter.MDC_KEY),
            expectedVersion));
  }

  @PostMapping("/{runId}/resume")
  ResponseEntity<ExecutionCommandView> resume(
      @PathVariable String runId,
      @org.springframework.web.bind.annotation.RequestHeader("Idempotency-Key")
          String idempotencyKey,
      @org.springframework.web.bind.annotation.RequestHeader("If-Match") long expectedVersion,
      Authentication authentication) {
    return accepted(
        dispatcher.accept(
            runId,
            RunExecutionCommand.Operation.RESUME,
            idempotencyKey,
            authentication.getName(),
            MDC.get(CorrelationIdFilter.MDC_KEY),
            expectedVersion));
  }

  @PostMapping("/{runId}/retry")
  ResponseEntity<ExecutionCommandView> retry(
      @PathVariable String runId,
      @org.springframework.web.bind.annotation.RequestHeader("Idempotency-Key")
          String idempotencyKey,
      @org.springframework.web.bind.annotation.RequestHeader("If-Match") long expectedVersion,
      Authentication authentication) {
    recovery.requireAction(runId, expectedVersion, "RETRY_CURRENT_STEP");
    return accepted(
        dispatcher.accept(
            runId,
            RunExecutionCommand.Operation.RETRY,
            idempotencyKey,
            authentication.getName(),
            MDC.get(CorrelationIdFilter.MDC_KEY),
            expectedVersion));
  }

  @PostMapping("/{runId}/replan")
  ResponseEntity<ExecutionCommandView> replan(
      @PathVariable String runId,
      @org.springframework.web.bind.annotation.RequestHeader("Idempotency-Key")
          String idempotencyKey,
      @org.springframework.web.bind.annotation.RequestHeader("If-Match") long expectedVersion,
      Authentication authentication) {
    recovery.requireAction(runId, expectedVersion, "REPLAN");
    return accepted(
        dispatcher.accept(
            runId,
            RunExecutionCommand.Operation.REPLAN,
            idempotencyKey,
            authentication.getName(),
            MDC.get(CorrelationIdFilter.MDC_KEY),
            expectedVersion));
  }

  @GetMapping("/{runId}/recovery")
  RunRecoveryService.RecoveryView recovery(@PathVariable String runId) {
    return recovery.inspect(runId);
  }

  @GetMapping("/execution-commands/{commandId}")
  ExecutionCommandView command(@PathVariable String commandId) {
    return ExecutionCommandView.from(dispatcher.get(commandId));
  }

  @PostMapping("/{runId}/approvals")
  ApprovalSubmissionView approve(
      @PathVariable String runId,
      @org.springframework.web.bind.annotation.RequestBody SubmitApprovalRequest request,
      Authentication authentication) {
    var actor = authentication.getName();
    securityContexts.requireIndependentApprover(runId, actor);
    changeReviews.requireCurrentApproval(runId, request.actionDigest(), request.planDigest());
    if (request.decision() == RuntimeApproval.Decision.DENIED
        && (request.reason() == null || request.reason().isBlank())) {
      throw new IllegalArgumentException("a rejection reason is required");
    }
    if (request.reason() != null && request.reason().length() > 1000) {
      throw new IllegalArgumentException("approval reason must not exceed 1000 characters");
    }
    var decisionRun =
        recovery.recordDecision(runId, request.expectedVersion(), actor, request.decision().name());
    approvals.save(
        new RuntimeApproval(
            runId,
            request.actionDigest(),
            request.planDigest(),
            actor,
            request.decision(),
            request.scope(),
            request.reason(),
            request.expiresAt() == null ? clock.instant().plusSeconds(900) : request.expiresAt()));
    return new ApprovalSubmissionView(execution(runId), decisionRun.version());
  }

  @GetMapping("/{runId}/change-review")
  ChangeReviewService.ChangeReview changeReview(@PathVariable String runId) {
    return changeReviews.review(runId);
  }

  @GetMapping("/{runId}/execution")
  AgentCheckpoint execution(@PathVariable String runId) {
    return checkpoints
        .load(runId)
        .orElseThrow(() -> new ResourceNotFoundException("agent run", runId));
  }

  @GetMapping(path = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter events(
      @PathVariable String runId,
      @org.springframework.web.bind.annotation.RequestHeader(
              value = "Last-Event-ID",
              required = false)
          String lastEventId) {
    return events.stream(runId, parseLastEventId(lastEventId));
  }

  private static int parseLastEventId(String value) {
    if (value == null || value.isBlank()) {
      return 0;
    }
    try {
      var sequence = Integer.parseInt(value);
      if (sequence < 0) {
        throw new IllegalArgumentException("Last-Event-ID must not be negative");
      }
      return sequence;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("Last-Event-ID must be a non-negative integer", exception);
    }
  }

  @GetMapping("/{runId}/approval-context")
  ApprovalContextView approvalContext(@PathVariable String runId) {
    var checkpoint = execution(runId);
    var action = checkpoint.pendingAction();
    if (action == null) {
      throw new IllegalStateException("run has no action waiting for approval");
    }
    return new ApprovalContextView(
        ActionDigests.create(action.tool(), action.input()),
        ActionDigests.create("change_plan", mapper.valueToTree(checkpoint.plan())),
        action.tool(),
        action.reason(),
        action.input());
  }

  @GetMapping("/{runId}/report")
  RunReport report(@PathVariable String runId) {
    return reports
        .load(runId)
        .orElseThrow(() -> new ResourceNotFoundException("agent run report", runId));
  }

  @GetMapping("/{runId}/artifact")
  TaskArtifact artifact(@PathVariable String runId) {
    var artifact = report(runId).artifact();
    if (artifact == null) {
      throw new ResourceNotFoundException("agent run artifact", runId);
    }
    return artifact;
  }

  public record SubmitApprovalRequest(
      long expectedVersion,
      String actionDigest,
      String planDigest,
      RuntimeApproval.Decision decision,
      ApprovalScope scope,
      String reason,
      Instant expiresAt) {}

  public record ApprovalSubmissionView(AgentCheckpoint checkpoint, long runVersion) {}

  public record ApprovalContextView(
      String actionDigest, String planDigest, String tool, String reason, JsonNode input) {
    public ApprovalContextView {
      input = input.deepCopy();
    }

    @Override
    public JsonNode input() {
      return input.deepCopy();
    }
  }

  private static ResponseEntity<ExecutionCommandView> accepted(RunExecutionCommand command) {
    return ResponseEntity.accepted()
        .location(java.net.URI.create("/api/v1/runs/execution-commands/" + command.id()))
        .body(ExecutionCommandView.from(command));
  }

  public record ExecutionCommandView(
      String id,
      String runId,
      String operation,
      String status,
      int attempts,
      String correlationId,
      String error,
      Instant requestedAt,
      Instant updatedAt) {
    static ExecutionCommandView from(RunExecutionCommand command) {
      return new ExecutionCommandView(
          command.id(),
          command.runId(),
          command.operation().name(),
          command.status().name(),
          command.attempts(),
          command.correlationId(),
          command.error(),
          command.requestedAt(),
          command.updatedAt());
    }
  }
}

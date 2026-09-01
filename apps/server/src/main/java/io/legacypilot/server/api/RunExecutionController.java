package io.legacypilot.server.api;

import io.legacypilot.application.error.ResourceNotFoundException;
import io.legacypilot.observability.ReportStore;
import io.legacypilot.observability.RunReport;
import io.legacypilot.runtime.AgentCheckpoint;
import io.legacypilot.runtime.ApprovalScope;
import io.legacypilot.runtime.ApprovalStore;
import io.legacypilot.runtime.CheckpointStore;
import io.legacypilot.runtime.RuntimeApproval;
import io.legacypilot.server.security.RunSecurityContextStore;
import io.legacypilot.server.service.RunExecutionService;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/runs")
public final class RunExecutionController {
  private final RunExecutionService executions;
  private final CheckpointStore checkpoints;
  private final ReportStore reports;
  private final ApprovalStore approvals;
  private final RunSecurityContextStore securityContexts;
  private final Clock clock;

  public RunExecutionController(
      RunExecutionService executions,
      CheckpointStore checkpoints,
      ReportStore reports,
      ApprovalStore approvals,
      RunSecurityContextStore securityContexts,
      Clock clock) {
    this.executions = executions;
    this.checkpoints = checkpoints;
    this.reports = reports;
    this.approvals = approvals;
    this.securityContexts = securityContexts;
    this.clock = clock;
  }

  @PostMapping("/{runId}/execute")
  RunExecutionService.UnifiedRunResult execute(
      @PathVariable String runId, Authentication authentication) {
    return executions.execute(runId, authentication.getName());
  }

  @PostMapping("/{runId}/resume")
  RunExecutionService.UnifiedRunResult resume(@PathVariable String runId) {
    return executions.resume(runId);
  }

  @PostMapping("/{runId}/approvals")
  AgentCheckpoint approve(
      @PathVariable String runId,
      @org.springframework.web.bind.annotation.RequestBody SubmitApprovalRequest request,
      Authentication authentication) {
    var actor = authentication.getName();
    securityContexts.requireIndependentApprover(runId, actor);
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
    return execution(runId);
  }

  @GetMapping("/{runId}/execution")
  AgentCheckpoint execution(@PathVariable String runId) {
    return checkpoints
        .load(runId)
        .orElseThrow(() -> new ResourceNotFoundException("agent run", runId));
  }

  @GetMapping("/{runId}/report")
  RunReport report(@PathVariable String runId) {
    return reports
        .load(runId)
        .orElseThrow(() -> new ResourceNotFoundException("agent run report", runId));
  }

  public record SubmitApprovalRequest(
      String actionDigest,
      String planDigest,
      RuntimeApproval.Decision decision,
      ApprovalScope scope,
      String reason,
      Instant expiresAt) {}
}

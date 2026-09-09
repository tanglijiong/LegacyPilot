package io.legacypilot.server.api;

import io.legacypilot.application.error.ResourceNotFoundException;
import io.legacypilot.observability.ReportStore;
import io.legacypilot.observability.RunReport;
import io.legacypilot.observability.TaskArtifact;
import io.legacypilot.runtime.AgentCheckpoint;
import io.legacypilot.runtime.AgentRunRequest;
import io.legacypilot.runtime.AgentRuntime;
import io.legacypilot.runtime.AgentRuntimeResult;
import io.legacypilot.runtime.ApprovalScope;
import io.legacypilot.runtime.ApprovalStore;
import io.legacypilot.runtime.CheckpointStore;
import io.legacypilot.runtime.RecoveryCoordinator;
import io.legacypilot.runtime.RecoveryOutcome;
import io.legacypilot.runtime.RuntimeApproval;
import io.legacypilot.server.security.RunSecurityContextStore;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent-runs")
public class AgentRuntimeController {

  private final AgentRuntime runtime;
  private final CheckpointStore checkpoints;
  private final ApprovalStore approvals;
  private final ReportStore reports;
  private final RecoveryCoordinator recovery;
  private final RunSecurityContextStore securityContexts;

  public AgentRuntimeController(
      AgentRuntime runtime,
      CheckpointStore checkpoints,
      ApprovalStore approvals,
      ReportStore reports,
      RecoveryCoordinator recovery,
      RunSecurityContextStore securityContexts) {
    this.runtime = runtime;
    this.checkpoints = checkpoints;
    this.approvals = approvals;
    this.reports = reports;
    this.recovery = recovery;
    this.securityContexts = securityContexts;
  }

  @PostMapping
  AgentRuntimeResult execute(@RequestBody AgentRunRequest request, Authentication authentication) {
    securityContexts.recordInitiator(request.runId(), authentication.getName());
    return runtime.execute(request);
  }

  @GetMapping("/{runId}")
  AgentCheckpoint checkpoint(@PathVariable String runId) {
    return checkpoints
        .load(runId)
        .orElseThrow(() -> new ResourceNotFoundException("agent run", runId));
  }

  @PostMapping("/{runId}/approvals")
  AgentCheckpoint approve(
      @PathVariable String runId,
      @RequestBody SubmitApprovalRequest request,
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
            request.expiresAt()));
    return checkpoint(runId);
  }

  @PostMapping("/{runId}/resume")
  AgentRuntimeResult resume(@PathVariable String runId) {
    return runtime.resume(runId);
  }

  @PostMapping("/recovery")
  List<RecoveryOutcome> recover() {
    return recovery.recoverAll();
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
      String actionDigest,
      String planDigest,
      String actor,
      RuntimeApproval.Decision decision,
      ApprovalScope scope,
      String reason,
      Instant expiresAt) {}
}

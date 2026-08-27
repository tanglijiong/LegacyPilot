package io.legacypilot.server.api;

import io.legacypilot.observability.ReportStore;
import io.legacypilot.observability.RunReport;
import io.legacypilot.runtime.AgentCheckpoint;
import io.legacypilot.runtime.AgentRunRequest;
import io.legacypilot.runtime.AgentRuntime;
import io.legacypilot.runtime.AgentRuntimeResult;
import io.legacypilot.runtime.ApprovalScope;
import io.legacypilot.runtime.ApprovalStore;
import io.legacypilot.runtime.CheckpointStore;
import io.legacypilot.runtime.RuntimeApproval;
import java.time.Instant;
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

  public AgentRuntimeController(
      AgentRuntime runtime,
      CheckpointStore checkpoints,
      ApprovalStore approvals,
      ReportStore reports) {
    this.runtime = runtime;
    this.checkpoints = checkpoints;
    this.approvals = approvals;
    this.reports = reports;
  }

  @PostMapping
  AgentRuntimeResult execute(@RequestBody AgentRunRequest request) {
    return runtime.execute(request);
  }

  @GetMapping("/{runId}")
  AgentCheckpoint checkpoint(@PathVariable String runId) {
    return checkpoints
        .load(runId)
        .orElseThrow(() -> new IllegalArgumentException("agent run was not found"));
  }

  @PostMapping("/{runId}/approvals")
  AgentCheckpoint approve(@PathVariable String runId, @RequestBody SubmitApprovalRequest request) {
    approvals.save(
        new RuntimeApproval(
            runId,
            request.actionDigest(),
            request.planDigest(),
            request.actor(),
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

  @GetMapping("/{runId}/report")
  RunReport report(@PathVariable String runId) {
    return reports
        .load(runId)
        .orElseThrow(() -> new IllegalArgumentException("agent run report was not found"));
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

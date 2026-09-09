package io.legacypilot.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.legacypilot.application.error.ResourceNotFoundException;
import io.legacypilot.model.ModelUsage;
import io.legacypilot.observability.ReportStore;
import io.legacypilot.observability.RunReport;
import io.legacypilot.observability.SourceEvidence;
import io.legacypilot.observability.TaskArtifact;
import io.legacypilot.runtime.AgentCheckpoint;
import io.legacypilot.runtime.AgentRunRequest;
import io.legacypilot.runtime.AgentRuntime;
import io.legacypilot.runtime.AgentRuntimeResult;
import io.legacypilot.runtime.ApprovalScope;
import io.legacypilot.runtime.ApprovalStore;
import io.legacypilot.runtime.CheckpointStore;
import io.legacypilot.runtime.RecoveryCoordinator;
import io.legacypilot.runtime.RuntimeApproval;
import io.legacypilot.runtime.RuntimeStatus;
import io.legacypilot.server.security.RunSecurityContextStore;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AgentRuntimeControllerTest {

  @Test
  void exposesExecutionApprovalResumeCheckpointAndReport() {
    var runtime = mock(AgentRuntime.class);
    var checkpoints = mock(CheckpointStore.class);
    var approvals = mock(ApprovalStore.class);
    var reports = mock(ReportStore.class);
    var recovery = mock(RecoveryCoordinator.class);
    var securityContexts = mock(RunSecurityContextStore.class);
    var controller =
        new AgentRuntimeController(
            runtime, checkpoints, approvals, reports, recovery, securityContexts);
    var developer =
        UsernamePasswordAuthenticationToken.authenticated("developer-a", "n/a", List.of());
    var approver =
        UsernamePasswordAuthenticationToken.authenticated("approver-a", "n/a", List.of());
    var checkpoint = checkpoint();
    var result = new AgentRuntimeResult(checkpoint, null, null);
    var request = mock(AgentRunRequest.class);
    var report = report();
    when(runtime.execute(request)).thenReturn(result);
    when(runtime.resume("run-1")).thenReturn(result);
    when(recovery.recoverAll()).thenReturn(List.of());
    when(checkpoints.load("run-1")).thenReturn(Optional.of(checkpoint));
    when(reports.load("run-1")).thenReturn(Optional.of(report));

    when(request.runId()).thenReturn("run-1");
    assertEquals(result, controller.execute(request, developer));
    assertEquals(checkpoint, controller.checkpoint("run-1"));
    var approval =
        new AgentRuntimeController.SubmitApprovalRequest(
            "action",
            "plan",
            "reviewer",
            RuntimeApproval.Decision.APPROVED,
            ApprovalScope.ONCE,
            "safe",
            Instant.now().plusSeconds(60));
    assertEquals(checkpoint, controller.approve("run-1", approval, approver));
    assertEquals(result, controller.resume("run-1"));
    assertEquals(report, controller.report("run-1"));
    assertEquals(report.artifact(), controller.artifact("run-1"));
    assertEquals(List.of(), controller.recover());
    verify(approvals).save(any(RuntimeApproval.class));
    var savedApproval = ArgumentCaptor.forClass(RuntimeApproval.class);
    verify(approvals).save(savedApproval.capture());
    assertEquals("approver-a", savedApproval.getValue().actor());
    assertNotEquals("reviewer", savedApproval.getValue().actor());
    verify(securityContexts).recordInitiator("run-1", "developer-a");
    verify(securityContexts).requireIndependentApprover("run-1", "approver-a");
  }

  @Test
  void rejectsMissingCheckpointAndReport() {
    var controller =
        new AgentRuntimeController(
            mock(AgentRuntime.class),
            mock(CheckpointStore.class),
            mock(ApprovalStore.class),
            mock(ReportStore.class),
            mock(RecoveryCoordinator.class),
            mock(RunSecurityContextStore.class));
    assertThrows(ResourceNotFoundException.class, () -> controller.checkpoint("missing"));
    assertThrows(ResourceNotFoundException.class, () -> controller.report("missing"));
    assertThrows(ResourceNotFoundException.class, () -> controller.artifact("missing"));
  }

  private static AgentCheckpoint checkpoint() {
    return new AgentCheckpoint(
        "run-1",
        RuntimeStatus.WAITING_FOR_APPROVAL,
        null,
        0,
        0,
        ModelUsage.NONE,
        Instant.EPOCH,
        Instant.EPOCH,
        null,
        "",
        0,
        "waiting");
  }

  private static RunReport report() {
    var artifact =
        new TaskArtifact(
            TaskArtifact.CURRENT_SCHEMA_VERSION,
            "ANALYZE",
            "grounded result",
            Map.of("entryPoints", List.of("TransferService is the entry point")),
            List.of(
                new SourceEvidence(
                    "src/TransferService.java",
                    "TransferService",
                    1,
                    2,
                    "class TransferService",
                    "TransferService is the entry point",
                    "abc123")),
            List.of());
    return new RunReport(
        "run-1",
        "WAITING_FOR_APPROVAL",
        "waiting",
        0,
        0,
        BigDecimal.ZERO,
        Duration.ZERO,
        "UNKNOWN",
        List.of(),
        List.of(),
        List.of(),
        "ANALYZE",
        artifact,
        Map.of("unsupportedClaimCount", "0"));
  }
}

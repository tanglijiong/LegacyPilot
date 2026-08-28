package io.legacypilot.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.legacypilot.model.ModelUsage;
import io.legacypilot.observability.ReportStore;
import io.legacypilot.observability.RunReport;
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
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentRuntimeControllerTest {

  @Test
  void exposesExecutionApprovalResumeCheckpointAndReport() {
    var runtime = mock(AgentRuntime.class);
    var checkpoints = mock(CheckpointStore.class);
    var approvals = mock(ApprovalStore.class);
    var reports = mock(ReportStore.class);
    var recovery = mock(RecoveryCoordinator.class);
    var controller = new AgentRuntimeController(runtime, checkpoints, approvals, reports, recovery);
    var checkpoint = checkpoint();
    var result = new AgentRuntimeResult(checkpoint, null, null);
    var request = mock(AgentRunRequest.class);
    var report = report();
    when(runtime.execute(request)).thenReturn(result);
    when(runtime.resume("run-1")).thenReturn(result);
    when(recovery.recoverAll()).thenReturn(List.of());
    when(checkpoints.load("run-1")).thenReturn(Optional.of(checkpoint));
    when(reports.load("run-1")).thenReturn(Optional.of(report));

    assertEquals(result, controller.execute(request));
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
    assertEquals(checkpoint, controller.approve("run-1", approval));
    assertEquals(result, controller.resume("run-1"));
    assertEquals(report, controller.report("run-1"));
    assertEquals(List.of(), controller.recover());
    verify(approvals).save(any(RuntimeApproval.class));
  }

  @Test
  void rejectsMissingCheckpointAndReport() {
    var controller =
        new AgentRuntimeController(
            mock(AgentRuntime.class),
            mock(CheckpointStore.class),
            mock(ApprovalStore.class),
            mock(ReportStore.class),
            mock(RecoveryCoordinator.class));
    assertThrows(IllegalArgumentException.class, () -> controller.checkpoint("missing"));
    assertThrows(IllegalArgumentException.class, () -> controller.report("missing"));
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
        List.of());
  }
}

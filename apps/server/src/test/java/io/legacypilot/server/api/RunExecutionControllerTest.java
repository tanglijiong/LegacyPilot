package io.legacypilot.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.legacypilot.application.execution.RunExecutionCommand;
import io.legacypilot.model.ModelUsage;
import io.legacypilot.observability.ReportStore;
import io.legacypilot.runtime.AgentAction;
import io.legacypilot.runtime.AgentCheckpoint;
import io.legacypilot.runtime.ApprovalStore;
import io.legacypilot.runtime.ChangePlan;
import io.legacypilot.runtime.CheckpointStore;
import io.legacypilot.runtime.RuntimeStatus;
import io.legacypilot.server.security.RunSecurityContextStore;
import io.legacypilot.server.service.RunEventStreamService;
import io.legacypilot.server.service.RunExecutionDispatcher;
import io.legacypilot.server.service.RunRecoveryService;
import io.legacypilot.tool.spi.ActionDigests;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class RunExecutionControllerTest {

  @Test
  void acceptsExecutionWithoutWaitingForTheAgent() {
    var dispatcher = mock(RunExecutionDispatcher.class);
    var command =
        new RunExecutionCommand(
            "command-1",
            "run-1",
            RunExecutionCommand.Operation.EXECUTE,
            2,
            "execute-run-1",
            "developer-a",
            "correlation-1",
            RunExecutionCommand.Status.QUEUED,
            0,
            null,
            null,
            "",
            Instant.EPOCH,
            Instant.EPOCH);
    when(dispatcher.accept(
            "run-1",
            RunExecutionCommand.Operation.EXECUTE,
            "execute-run-1",
            "developer-a",
            null,
            2L))
        .thenReturn(command);
    var controller = controller(dispatcher, mock(CheckpointStore.class), new ObjectMapper());

    var response =
        controller.execute(
            "run-1",
            "execute-run-1",
            2,
            UsernamePasswordAuthenticationToken.authenticated("developer-a", "", List.of()));

    assertEquals(202, response.getStatusCode().value());
    assertEquals(
        "/api/v1/runs/execution-commands/command-1",
        response.getHeaders().getLocation().toString());
    assertEquals("QUEUED", response.getBody().status());
  }

  @Test
  void exposesDigestsRequiredByTheApprovalApi() {
    var checkpoints = mock(CheckpointStore.class);
    var mapper = new ObjectMapper();
    var input = JsonNodeFactory.instance.objectNode().put("path", "src/TransferService.java");
    var action = new AgentAction("TOOL", "apply_patch", input, "apply reviewed change");
    var plan =
        new ChangePlan(
            1,
            List.of("change service"),
            List.of("src/TransferService.java"),
            "MEDIUM",
            "limit transfer");
    var checkpoint =
        new AgentCheckpoint(
            "run-1",
            RuntimeStatus.WAITING_FOR_APPROVAL,
            plan,
            1,
            0,
            ModelUsage.NONE,
            Instant.EPOCH,
            Instant.EPOCH,
            action,
            "",
            0,
            "waiting");
    when(checkpoints.load("run-1")).thenReturn(Optional.of(checkpoint));
    var controller = controller(mock(RunExecutionDispatcher.class), checkpoints, mapper);

    var context = controller.approvalContext("run-1");

    assertEquals(ActionDigests.create(action.tool(), action.input()), context.actionDigest());
    assertEquals(
        ActionDigests.create("change_plan", mapper.valueToTree(plan)), context.planDigest());
    assertEquals("apply_patch", context.tool());
  }

  @Test
  void forwardsLastEventIdForReconnectAndRejectsInvalidValues() {
    var eventStream = mock(RunEventStreamService.class);
    var emitter = new SseEmitter();
    when(eventStream.stream("run-1", 7)).thenReturn(emitter);
    var controller =
        controller(
            mock(RunExecutionDispatcher.class),
            mock(CheckpointStore.class),
            new ObjectMapper(),
            eventStream);

    assertEquals(emitter, controller.events("run-1", "7"));
    assertThrows(IllegalArgumentException.class, () -> controller.events("run-1", "invalid"));
  }

  @Test
  void acceptsRetryAndReplanOnlyAfterRecoveryPolicyAndVersionChecks() {
    var dispatcher = mock(RunExecutionDispatcher.class);
    var recovery = mock(RunRecoveryService.class);
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated("developer-a", "", List.of());
    var retry = command("command-retry", RunExecutionCommand.Operation.RETRY, 7);
    var replan = command("command-replan", RunExecutionCommand.Operation.REPLAN, 8);
    when(dispatcher.accept(
            "run-1", RunExecutionCommand.Operation.RETRY, "retry-run-1-7", "developer-a", null, 7L))
        .thenReturn(retry);
    when(dispatcher.accept(
            "run-1",
            RunExecutionCommand.Operation.REPLAN,
            "replan-run-1-8",
            "developer-a",
            null,
            8L))
        .thenReturn(replan);
    var controller =
        controller(dispatcher, mock(CheckpointStore.class), new ObjectMapper(), recovery);

    assertEquals(
        202, controller.retry("run-1", "retry-run-1-7", 7, authentication).getStatusCode().value());
    assertEquals(
        202,
        controller.replan("run-1", "replan-run-1-8", 8, authentication).getStatusCode().value());

    verify(recovery).requireAction("run-1", 7, "RETRY_CURRENT_STEP");
    verify(recovery).requireAction("run-1", 8, "REPLAN");
  }

  @Test
  void exposesTheServerRecoveryDecision() {
    var recovery = mock(RunRecoveryService.class);
    var view =
        new RunRecoveryService.RecoveryView(
            "run-1",
            "FAILED",
            7,
            "MODEL_FAILURE",
            "SAFE_FROM_CHECKPOINT",
            "timeout",
            List.of("RETRY_CURRENT_STEP"),
            List.of(),
            Instant.EPOCH);
    when(recovery.inspect("run-1")).thenReturn(view);
    var controller =
        controller(
            mock(RunExecutionDispatcher.class),
            mock(CheckpointStore.class),
            new ObjectMapper(),
            recovery);

    assertEquals(view, controller.recovery("run-1"));
  }

  private static RunExecutionController controller(
      RunExecutionDispatcher dispatcher, CheckpointStore checkpoints, ObjectMapper mapper) {
    return controller(dispatcher, checkpoints, mapper, mock(RunEventStreamService.class));
  }

  private static RunExecutionController controller(
      RunExecutionDispatcher dispatcher,
      CheckpointStore checkpoints,
      ObjectMapper mapper,
      RunRecoveryService recovery) {
    return new RunExecutionController(
        dispatcher,
        checkpoints,
        mock(ReportStore.class),
        mock(ApprovalStore.class),
        mock(RunSecurityContextStore.class),
        Clock.systemUTC(),
        mapper,
        mock(RunEventStreamService.class),
        mock(io.legacypilot.server.service.ChangeReviewService.class),
        recovery);
  }

  private static RunExecutionController controller(
      RunExecutionDispatcher dispatcher,
      CheckpointStore checkpoints,
      ObjectMapper mapper,
      RunEventStreamService eventStream) {
    return new RunExecutionController(
        dispatcher,
        checkpoints,
        mock(ReportStore.class),
        mock(ApprovalStore.class),
        mock(RunSecurityContextStore.class),
        Clock.systemUTC(),
        mapper,
        eventStream,
        mock(io.legacypilot.server.service.ChangeReviewService.class),
        mock(io.legacypilot.server.service.RunRecoveryService.class));
  }

  private static RunExecutionCommand command(
      String id, RunExecutionCommand.Operation operation, long version) {
    return new RunExecutionCommand(
        id,
        "run-1",
        operation,
        version,
        operation.name().toLowerCase(java.util.Locale.ROOT) + "-run-1-" + version,
        "developer-a",
        "correlation-1",
        RunExecutionCommand.Status.QUEUED,
        0,
        null,
        null,
        "",
        Instant.EPOCH,
        Instant.EPOCH);
  }
}

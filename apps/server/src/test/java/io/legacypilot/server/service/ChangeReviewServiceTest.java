package io.legacypilot.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.application.port.WorkspaceDescriptor;
import io.legacypilot.application.port.WorkspaceService;
import io.legacypilot.application.service.GetProjectUseCase;
import io.legacypilot.application.service.GetRunStatusUseCase;
import io.legacypilot.application.service.GetTaskUseCase;
import io.legacypilot.domain.project.Project;
import io.legacypilot.domain.project.ProjectId;
import io.legacypilot.domain.run.RunId;
import io.legacypilot.domain.run.RunStatus;
import io.legacypilot.domain.run.TaskRun;
import io.legacypilot.domain.run.WorkspaceId;
import io.legacypilot.domain.task.Task;
import io.legacypilot.domain.task.TaskId;
import io.legacypilot.domain.task.TaskMode;
import io.legacypilot.model.ModelUsage;
import io.legacypilot.observability.ReportStore;
import io.legacypilot.observability.TraceSink;
import io.legacypilot.runtime.AgentAction;
import io.legacypilot.runtime.AgentCheckpoint;
import io.legacypilot.runtime.ChangePlan;
import io.legacypilot.runtime.CheckpointStore;
import io.legacypilot.runtime.RuntimeStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChangeReviewServiceTest {
  @TempDir Path workspace;

  private final GetRunStatusUseCase getRun = mock(GetRunStatusUseCase.class);
  private final GetTaskUseCase getTask = mock(GetTaskUseCase.class);
  private final GetProjectUseCase getProject = mock(GetProjectUseCase.class);
  private final WorkspaceService workspaces = mock(WorkspaceService.class);
  private final CheckpointStore checkpoints = mock(CheckpointStore.class);
  private final ReportStore reports = mock(ReportStore.class);
  private final TraceSink trace = mock(TraceSink.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private ChangeReviewService service;

  @BeforeEach
  void setUp() throws Exception {
    git("init", "--initial-branch=main");
    git("config", "user.name", "LegacyPilot Test");
    git("config", "user.email", "test@legacypilot.invalid");
    Files.writeString(
        workspace.resolve("Transfer.java"), "class Transfer {\n  int limit = 10;\n}\n");
    git("add", "Transfer.java");
    git("commit", "-m", "baseline");

    var run = mock(TaskRun.class);
    var task = mock(Task.class);
    var project = mock(Project.class);
    var runId = new RunId("run-1");
    var workspaceId = new WorkspaceId("run-1");
    when(run.id()).thenReturn(runId);
    when(run.taskId()).thenReturn(new TaskId("task-1"));
    when(run.workspaceId()).thenReturn(workspaceId);
    when(run.status()).thenReturn(RunStatus.WAITING_FOR_APPROVAL);
    when(getRun.get("run-1")).thenReturn(run);
    when(task.mode()).thenReturn(TaskMode.CHANGE);
    when(task.projectId()).thenReturn(new ProjectId("project-1"));
    when(getTask.get("task-1")).thenReturn(task);
    when(getProject.get("project-1")).thenReturn(project);
    when(workspaces.open(project, runId, workspaceId))
        .thenReturn(new WorkspaceDescriptor(workspaceId, workspace.toString()));
    when(reports.load("run-1")).thenReturn(Optional.empty());
    when(trace.events("run-1")).thenReturn(List.of());
    service =
        new ChangeReviewService(
            getRun, getTask, getProject, workspaces, checkpoints, reports, trace, mapper);
  }

  @Test
  void buildsServerAuthoritativePreviewAndRejectsStaleDigests() {
    var input =
        mapper
            .createObjectNode()
            .put("path", "Transfer.java")
            .put("expectedSha256", "a".repeat(64))
            .put("replacement", "class Transfer {\n  int limit = 20;\n}\n");
    var plan =
        new ChangePlan(
            2,
            List.of("update transfer limit", "run verification"),
            List.of("Transfer.java"),
            "MEDIUM",
            "Enforce the approved transfer limit");
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
            new AgentAction("TOOL", "apply_patch", input, "apply reviewed change"),
            "",
            0,
            "waiting");
    when(checkpoints.load("run-1")).thenReturn(Optional.of(checkpoint));

    var review = service.review("run-1");

    assertEquals("Modify Transfer.java using apply_patch", review.action().summary());
    assertEquals(1, review.diff().getFirst().additions());
    assertEquals(1, review.diff().getFirst().deletions());
    assertTrue(
        review.diff().getFirst().lines().stream()
            .anyMatch(line -> line.kind().equals("ADD") && line.text().contains("20")));
    service.requireCurrentApproval(
        "run-1", review.action().actionDigest(), review.action().planDigest());
    assertThrows(
        IllegalStateException.class,
        () -> service.requireCurrentApproval("run-1", "stale", review.action().planDigest()));
    assertThrows(
        IllegalStateException.class,
        () -> service.requireCurrentApproval("run-1", review.action().actionDigest(), "stale"));
  }

  private void git(String... arguments) throws Exception {
    var command = new java.util.ArrayList<String>();
    command.add("git");
    command.addAll(List.of(arguments));
    var process = new ProcessBuilder(command).directory(workspace.toFile()).start();
    assertEquals(0, process.waitFor());
  }
}

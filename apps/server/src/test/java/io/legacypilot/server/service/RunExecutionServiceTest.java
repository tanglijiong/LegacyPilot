package io.legacypilot.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.legacypilot.analysis.java.JavaProjectIndexer;
import io.legacypilot.analysis.java.ProjectIndex;
import io.legacypilot.application.port.TaskRunRepository;
import io.legacypilot.application.port.WorkspaceDescriptor;
import io.legacypilot.application.port.WorkspaceService;
import io.legacypilot.application.service.GetProjectUseCase;
import io.legacypilot.application.service.GetRunStatusUseCase;
import io.legacypilot.application.service.GetTaskUseCase;
import io.legacypilot.domain.project.GitRevision;
import io.legacypilot.domain.project.Project;
import io.legacypilot.domain.project.ProjectId;
import io.legacypilot.domain.project.RepositoryLocation;
import io.legacypilot.domain.run.RunId;
import io.legacypilot.domain.run.RunStatus;
import io.legacypilot.domain.run.TaskRun;
import io.legacypilot.domain.run.WorkspaceId;
import io.legacypilot.domain.task.AcceptanceCriteria;
import io.legacypilot.domain.task.Requirement;
import io.legacypilot.domain.task.Task;
import io.legacypilot.domain.task.TaskId;
import io.legacypilot.model.ModelUsage;
import io.legacypilot.runtime.AgentCheckpoint;
import io.legacypilot.runtime.AgentRuntime;
import io.legacypilot.runtime.AgentRuntimeResult;
import io.legacypilot.runtime.RuntimeStatus;
import io.legacypilot.server.security.RunSecurityContextStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunExecutionServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");
  private static final String REVISION = "a".repeat(40);

  @TempDir Path workspace;

  private GetRunStatusUseCase getRun;
  private GetTaskUseCase getTask;
  private GetProjectUseCase getProject;
  private TaskRunRepository runs;
  private WorkspaceService workspaces;
  private JavaProjectIndexer indexer;
  private AgentRuntime runtime;
  private RunSecurityContextStore securityContexts;
  private RunExecutionService service;

  @BeforeEach
  void setUp() {
    getRun = mock(GetRunStatusUseCase.class);
    getTask = mock(GetTaskUseCase.class);
    getProject = mock(GetProjectUseCase.class);
    runs = mock(TaskRunRepository.class);
    workspaces = mock(WorkspaceService.class);
    indexer = mock(JavaProjectIndexer.class);
    runtime = mock(AgentRuntime.class);
    securityContexts = mock(RunSecurityContextStore.class);
    when(runs.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
    service =
        new RunExecutionService(
            getRun,
            getTask,
            getProject,
            runs,
            workspaces,
            indexer,
            runtime,
            Clock.fixed(NOW, ZoneOffset.UTC),
            securityContexts,
            "local-model",
            12_000,
            50,
            2,
            20,
            2,
            100_000,
            new BigDecimal("10.00"),
            Duration.ofMinutes(30));
  }

  @Test
  void buildsInternalAgentRequestAndSynchronizesSuccess() {
    var fixture = fixture();
    when(runtime.execute(any())).thenReturn(result(RuntimeStatus.SUCCEEDED));

    var result = service.execute(fixture.run().id().value(), "developer-a");

    assertEquals(RunStatus.SUCCEEDED, result.status());
    assertEquals(6, result.lifecycleEvents());
    verify(indexer).index(workspace, REVISION);
    verify(runtime).execute(any());
    verify(securityContexts).recordInitiator("run-1", "developer-a");
  }

  @Test
  void exposesApprovalPauseThroughTheLifecycleRun() {
    var fixture = fixture();
    when(runtime.execute(any())).thenReturn(result(RuntimeStatus.WAITING_FOR_APPROVAL));

    var result = service.execute(fixture.run().id().value(), "developer-a");

    assertEquals(RunStatus.WAITING_FOR_APPROVAL, result.status());
  }

  private Fixture fixture() {
    var runId = new RunId("run-1");
    var taskId = new TaskId("task-1");
    var projectId = new ProjectId("project-1");
    var run =
        TaskRun.create(runId, taskId, NOW)
            .transitionTo(RunStatus.PREPARING_WORKSPACE, NOW, "start")
            .workspaceReady(new WorkspaceId(runId.value()), NOW);
    var task =
        new Task(
            taskId,
            projectId,
            new Requirement("make a safe change"),
            new AcceptanceCriteria(List.of("tests pass")),
            NOW);
    var project =
        new Project(
            projectId,
            new RepositoryLocation("/source"),
            "/source",
            new GitRevision(REVISION),
            NOW);
    when(getRun.get(runId.value())).thenReturn(run);
    when(getTask.get(taskId.value())).thenReturn(task);
    when(getProject.get(projectId.value())).thenReturn(project);
    when(workspaces.open(project, runId, run.workspaceId()))
        .thenReturn(new WorkspaceDescriptor(run.workspaceId(), workspace.toString()));
    when(indexer.index(workspace, REVISION))
        .thenReturn(new ProjectIndex(1, REVISION, List.of(), List.of(), List.of()));
    return new Fixture(run);
  }

  private static AgentRuntimeResult result(RuntimeStatus status) {
    return new AgentRuntimeResult(
        new AgentCheckpoint(
            "run-1", status, null, 0, 0, ModelUsage.NONE, NOW, NOW, null, "", 0, ""),
        null,
        null);
  }

  private record Fixture(TaskRun run) {}
}

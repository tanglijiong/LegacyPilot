package io.legacypilot.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import io.legacypilot.domain.task.Task;
import io.legacypilot.domain.task.TaskId;
import io.legacypilot.domain.task.TaskMode;
import io.legacypilot.observability.TraceSink;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChangeApplicationServiceTest {
  @TempDir Path root;

  private final GetRunStatusUseCase getRun = mock(GetRunStatusUseCase.class);
  private final GetTaskUseCase getTask = mock(GetTaskUseCase.class);
  private final GetProjectUseCase getProject = mock(GetProjectUseCase.class);
  private final WorkspaceService workspaces = mock(WorkspaceService.class);
  private final ChangeReviewService reviews = mock(ChangeReviewService.class);
  private final TraceSink trace = mock(TraceSink.class);
  private Path target;
  private ChangeApplicationService service;

  @BeforeEach
  void setUp() throws Exception {
    var repository = root.resolve("repository");
    var workspace = root.resolve("workspace");
    target = root.resolve("target");
    Files.createDirectories(repository);
    Files.createDirectories(workspace);
    Files.createDirectories(target);
    git(repository, "init", "--initial-branch=main");
    git(repository, "config", "user.name", "LegacyPilot Test");
    git(repository, "config", "user.email", "test@legacypilot.invalid");
    Files.writeString(repository.resolve("Existing.txt"), "baseline\n");
    git(repository, "add", "Existing.txt");
    git(repository, "commit", "-m", "baseline");
    var revision = git(repository, "rev-parse", "HEAD").trim();
    Files.writeString(target.resolve("Existing.txt"), "baseline\n");
    Files.writeString(workspace.resolve("Existing.txt"), "changed\n");
    Files.writeString(workspace.resolve("Added.txt"), "new\n");

    var run = mock(TaskRun.class);
    var task = mock(Task.class);
    var project =
        new Project(
            new ProjectId("project-1"),
            new RepositoryLocation("directory://snapshot/project"),
            repository.toString(),
            new GitRevision(revision),
            Instant.EPOCH);
    var runId = new RunId("run-1");
    var workspaceId = new WorkspaceId("run-1");
    when(run.id()).thenReturn(runId);
    when(run.taskId()).thenReturn(new TaskId("task-1"));
    when(run.workspaceId()).thenReturn(workspaceId);
    when(run.status()).thenReturn(RunStatus.SUCCEEDED);
    when(task.mode()).thenReturn(TaskMode.CHANGE);
    when(task.projectId()).thenReturn(new ProjectId("project-1"));
    when(getRun.get("run-1")).thenReturn(run);
    when(getTask.get("task-1")).thenReturn(task);
    when(getProject.get("project-1")).thenReturn(project);
    when(workspaces.open(project, runId, workspaceId))
        .thenReturn(new WorkspaceDescriptor(workspaceId, workspace.toString()));
    when(reviews.review("run-1"))
        .thenReturn(
            new ChangeReviewService.ChangeReview(
                "run-1",
                "SUCCEEDED",
                1,
                List.of(),
                List.of("Existing.txt", "Added.txt"),
                "LOW",
                "test",
                null,
                List.of(),
                List.of(
                    new ChangeReviewService.FileDiff(
                        "Existing.txt", "MODIFIED", 1, 1, false, List.of(), ""),
                    new ChangeReviewService.FileDiff(
                        "Added.txt", "ADDED", 1, 0, false, List.of(), "")),
                List.of(),
                null,
                false));
    service =
        new ChangeApplicationService(
            getRun,
            getTask,
            getProject,
            workspaces,
            reviews,
            trace,
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
  }

  @Test
  void appliesVerifiedChangesAndIsIdempotent() throws Exception {
    var first = service.apply("run-1", target.toString(), "approver");
    assertEquals("APPLIED", first.status());
    assertEquals("changed\n", Files.readString(target.resolve("Existing.txt")));
    assertEquals("new\n", Files.readString(target.resolve("Added.txt")));

    var second = service.apply("run-1", target.toString(), "approver");
    assertEquals("ALREADY_APPLIED", second.status());
    assertEquals(2, second.alreadyAppliedFiles());
  }

  @Test
  void refusesToOverwriteAConcurrentLocalChange() throws Exception {
    Files.writeString(target.resolve("Existing.txt"), "developer change\n");
    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> service.apply("run-1", target.toString(), "approver"));
    assertEquals(true, failure.getMessage().contains("拒绝覆盖"));
    assertEquals(false, Files.exists(target.resolve("Added.txt")));
  }

  private static String git(Path directory, String... arguments) throws Exception {
    var command = new java.util.ArrayList<String>();
    command.add("git");
    command.addAll(List.of(arguments));
    var process =
        new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    var output = new String(process.getInputStream().readAllBytes());
    assertEquals(0, process.waitFor(), output);
    return output;
  }
}

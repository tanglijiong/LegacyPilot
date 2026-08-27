package io.legacypilot.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.legacypilot.application.error.ResourceNotFoundException;
import io.legacypilot.application.port.IdGenerator;
import io.legacypilot.application.port.ProjectRepository;
import io.legacypilot.application.port.RegisteredRepository;
import io.legacypilot.application.port.TaskRepository;
import io.legacypilot.application.port.TaskRunRepository;
import io.legacypilot.application.port.WorkspaceDescriptor;
import io.legacypilot.application.port.WorkspaceService;
import io.legacypilot.application.service.CancelRunUseCase;
import io.legacypilot.application.service.CreateTaskUseCase;
import io.legacypilot.application.service.GetProjectUseCase;
import io.legacypilot.application.service.GetRunStatusUseCase;
import io.legacypilot.application.service.GetTaskUseCase;
import io.legacypilot.application.service.RegisterProjectUseCase;
import io.legacypilot.application.service.StartRunUseCase;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LifecycleUseCasesTest {

  private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

  private final MemoryStore store = new MemoryStore();
  private final FakeWorkspace workspace = new FakeWorkspace();
  private final AtomicInteger sequence = new AtomicInteger();
  private final IdGenerator ids = prefix -> prefix + "-" + sequence.incrementAndGet();
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  @BeforeEach
  void reset() {
    store.projects.clear();
    store.tasks.clear();
    store.runs.clear();
    workspace.failCreate = false;
    workspace.cleanupCount = 0;
    sequence.set(0);
  }

  @Test
  void executesRegistrationTaskRunStatusAndCancellationFlow() {
    var project =
        new RegisterProjectUseCase(store, workspace, ids, clock).register("/repo", "HEAD");
    var task =
        new CreateTaskUseCase(store, store, ids, clock)
            .create(project.id().value(), "make a safe change", null);
    var run =
        new StartRunUseCase(store, store, store, workspace, ids, clock).start(task.id().value());

    assertEquals(project, new GetProjectUseCase(store).get(project.id().value()));
    assertEquals(task, new GetTaskUseCase(store).get(task.id().value()));
    assertEquals(RunStatus.WORKSPACE_READY, run.status());
    assertEquals(run, new GetRunStatusUseCase(store).get(run.id().value()));

    var cancelled =
        new CancelRunUseCase(store, store, store, workspace, clock).cancel(run.id().value());
    assertEquals(RunStatus.CANCELLED, cancelled.status());
    assertEquals(1, workspace.cleanupCount);
  }

  @Test
  void reportsMissingResources() {
    assertThrows(
        ResourceNotFoundException.class, () -> new GetProjectUseCase(store).get("missing"));
    assertThrows(ResourceNotFoundException.class, () -> new GetTaskUseCase(store).get("missing"));
    assertThrows(
        ResourceNotFoundException.class, () -> new GetRunStatusUseCase(store).get("missing"));
    assertThrows(
        ResourceNotFoundException.class,
        () -> new CreateTaskUseCase(store, store, ids, clock).create("missing", "work", null));
    assertThrows(
        ResourceNotFoundException.class,
        () -> new StartRunUseCase(store, store, store, workspace, ids, clock).start("missing"));
  }

  @Test
  void persistsFailedStateWhenWorkspaceCreationFails() {
    var project = new RegisterProjectUseCase(store, workspace, ids, clock).register("/repo", null);
    var task =
        new CreateTaskUseCase(store, store, ids, clock)
            .create(project.id().value(), "work", java.util.List.of("safe"));
    workspace.failCreate = true;

    assertThrows(
        IllegalStateException.class,
        () ->
            new StartRunUseCase(store, store, store, workspace, ids, clock)
                .start(task.id().value()));
    assertEquals(RunStatus.FAILED, store.runs.values().iterator().next().status());
  }

  private static final class MemoryStore
      implements ProjectRepository, TaskRepository, TaskRunRepository {
    private final Map<ProjectId, Project> projects = new HashMap<>();
    private final Map<TaskId, Task> tasks = new HashMap<>();
    private final Map<RunId, TaskRun> runs = new HashMap<>();

    @Override
    public void add(Project project) {
      projects.put(project.id(), project);
    }

    @Override
    public Optional<Project> findById(ProjectId id) {
      return Optional.ofNullable(projects.get(id));
    }

    @Override
    public void add(Task task) {
      tasks.put(task.id(), task);
    }

    @Override
    public Optional<Task> findById(TaskId id) {
      return Optional.ofNullable(tasks.get(id));
    }

    @Override
    public TaskRun add(TaskRun run) {
      runs.put(run.id(), run);
      return run;
    }

    @Override
    public TaskRun update(TaskRun run) {
      var updated = run.withVersion(run.version() + 1);
      runs.put(updated.id(), updated);
      return updated;
    }

    @Override
    public Optional<TaskRun> findById(RunId id) {
      return Optional.ofNullable(runs.get(id));
    }
  }

  private static final class FakeWorkspace implements WorkspaceService {
    private boolean failCreate;
    private int cleanupCount;

    @Override
    public RegisteredRepository register(RepositoryLocation location, String requestedRevision) {
      return new RegisteredRepository(
          location.value(), new GitRevision("0123456789012345678901234567890123456789"));
    }

    @Override
    public WorkspaceDescriptor create(Project project, RunId runId) {
      if (failCreate) {
        throw new IllegalStateException("workspace failed");
      }
      return new WorkspaceDescriptor(new WorkspaceId(runId.value()), "/work/" + runId.value());
    }

    @Override
    public void cleanup(Project project, RunId runId, WorkspaceId workspaceId) {
      cleanupCount++;
    }
  }
}

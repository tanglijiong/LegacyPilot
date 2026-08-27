package io.legacypilot.server.api;

import io.legacypilot.application.service.CancelRunUseCase;
import io.legacypilot.application.service.CreateTaskUseCase;
import io.legacypilot.application.service.GetProjectUseCase;
import io.legacypilot.application.service.GetRunStatusUseCase;
import io.legacypilot.application.service.GetTaskUseCase;
import io.legacypilot.application.service.RegisterProjectUseCase;
import io.legacypilot.application.service.StartRunUseCase;
import io.legacypilot.domain.project.Project;
import io.legacypilot.domain.run.TaskRun;
import io.legacypilot.domain.task.Task;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class LifecycleController {

  private final RegisterProjectUseCase registerProject;
  private final GetProjectUseCase getProject;
  private final CreateTaskUseCase createTask;
  private final GetTaskUseCase getTask;
  private final StartRunUseCase startRun;
  private final GetRunStatusUseCase getRun;
  private final CancelRunUseCase cancelRun;

  public LifecycleController(
      RegisterProjectUseCase registerProject,
      GetProjectUseCase getProject,
      CreateTaskUseCase createTask,
      GetTaskUseCase getTask,
      StartRunUseCase startRun,
      GetRunStatusUseCase getRun,
      CancelRunUseCase cancelRun) {
    this.registerProject = registerProject;
    this.getProject = getProject;
    this.createTask = createTask;
    this.getTask = getTask;
    this.startRun = startRun;
    this.getRun = getRun;
    this.cancelRun = cancelRun;
  }

  @PostMapping("/projects")
  ResponseEntity<ProjectView> register(@RequestBody RegisterProjectRequest request) {
    var result = ProjectView.from(registerProject.register(request.source(), request.revision()));
    return ResponseEntity.created(URI.create("/api/v1/projects/" + result.id())).body(result);
  }

  @GetMapping("/projects/{projectId}")
  ProjectView project(@PathVariable String projectId) {
    return ProjectView.from(getProject.get(projectId));
  }

  @PostMapping("/projects/{projectId}/tasks")
  ResponseEntity<TaskView> createTask(
      @PathVariable String projectId, @RequestBody CreateTaskRequest request) {
    var result =
        TaskView.from(createTask.create(projectId, request.requirement(), request.criteria()));
    return ResponseEntity.created(URI.create("/api/v1/tasks/" + result.id())).body(result);
  }

  @GetMapping("/tasks/{taskId}")
  TaskView task(@PathVariable String taskId) {
    return TaskView.from(getTask.get(taskId));
  }

  @PostMapping("/tasks/{taskId}/runs")
  ResponseEntity<RunView> start(@PathVariable String taskId) {
    var result = RunView.from(startRun.start(taskId));
    return ResponseEntity.created(URI.create("/api/v1/runs/" + result.id())).body(result);
  }

  @GetMapping("/runs/{runId}")
  RunView run(@PathVariable String runId) {
    return RunView.from(getRun.get(runId));
  }

  @PostMapping("/runs/{runId}/cancel")
  RunView cancel(@PathVariable String runId) {
    return RunView.from(cancelRun.cancel(runId));
  }

  public record RegisterProjectRequest(String source, String revision) {}

  public record CreateTaskRequest(String requirement, List<String> criteria) {
    public CreateTaskRequest {
      criteria = criteria == null ? null : List.copyOf(criteria);
    }
  }

  public record ProjectView(
      String id, String source, String repositoryPath, String baseRevision, Instant registeredAt) {
    static ProjectView from(Project project) {
      return new ProjectView(
          project.id().value(),
          project.source().value(),
          project.repositoryPath(),
          project.baseRevision().value(),
          project.registeredAt());
    }
  }

  public record TaskView(
      String id, String projectId, String requirement, List<String> criteria, Instant createdAt) {
    public TaskView {
      criteria = List.copyOf(criteria);
    }

    static TaskView from(Task task) {
      return new TaskView(
          task.id().value(),
          task.projectId().value(),
          task.requirement().text(),
          task.acceptanceCriteria().items(),
          task.createdAt());
    }
  }

  public record RunView(
      String id,
      String taskId,
      String status,
      long version,
      String workspaceId,
      String terminalReason,
      Instant createdAt,
      Instant updatedAt,
      List<TransitionView> history) {
    public RunView {
      history = List.copyOf(history);
    }

    static RunView from(TaskRun run) {
      return new RunView(
          run.id().value(),
          run.taskId().value(),
          run.status().name(),
          run.version(),
          run.workspaceId() == null ? null : run.workspaceId().value(),
          run.terminalReason(),
          run.createdAt(),
          run.updatedAt(),
          run.history().stream().map(TransitionView::from).toList());
    }
  }

  public record TransitionView(
      int sequence, String from, String to, Instant occurredAt, String reason) {
    static TransitionView from(io.legacypilot.domain.run.StatusTransition transition) {
      return new TransitionView(
          transition.sequence(),
          transition.from().name(),
          transition.to().name(),
          transition.occurredAt(),
          transition.reason());
    }
  }
}

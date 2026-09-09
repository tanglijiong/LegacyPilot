package io.legacypilot.server.api;

import io.legacypilot.application.port.DirectorySnapshotFile;
import io.legacypilot.application.service.CreateTaskUseCase;
import io.legacypilot.application.service.GetProjectUseCase;
import io.legacypilot.application.service.GetRunStatusUseCase;
import io.legacypilot.application.service.GetTaskUseCase;
import io.legacypilot.application.service.InspectProjectUseCase;
import io.legacypilot.application.service.ListProjectTasksUseCase;
import io.legacypilot.application.service.ListProjectsUseCase;
import io.legacypilot.application.service.RegisterProjectUseCase;
import io.legacypilot.application.service.RemoveProjectUseCase;
import io.legacypilot.application.service.StartRunUseCase;
import io.legacypilot.domain.project.Project;
import io.legacypilot.domain.run.TaskRun;
import io.legacypilot.domain.task.Task;
import io.legacypilot.domain.task.TaskMode;
import io.legacypilot.server.service.RunRecoveryService;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class LifecycleController {

  private final RegisterProjectUseCase registerProject;
  private final InspectProjectUseCase inspectProject;
  private final GetProjectUseCase getProject;
  private final ListProjectsUseCase listProjects;
  private final RemoveProjectUseCase removeProject;
  private final CreateTaskUseCase createTask;
  private final GetTaskUseCase getTask;
  private final ListProjectTasksUseCase listProjectTasks;
  private final StartRunUseCase startRun;
  private final GetRunStatusUseCase getRun;
  private final RunRecoveryService recovery;

  public LifecycleController(
      RegisterProjectUseCase registerProject,
      InspectProjectUseCase inspectProject,
      GetProjectUseCase getProject,
      ListProjectsUseCase listProjects,
      RemoveProjectUseCase removeProject,
      CreateTaskUseCase createTask,
      GetTaskUseCase getTask,
      ListProjectTasksUseCase listProjectTasks,
      StartRunUseCase startRun,
      GetRunStatusUseCase getRun,
      RunRecoveryService recovery) {
    this.registerProject = registerProject;
    this.inspectProject = inspectProject;
    this.getProject = getProject;
    this.listProjects = listProjects;
    this.removeProject = removeProject;
    this.createTask = createTask;
    this.getTask = getTask;
    this.listProjectTasks = listProjectTasks;
    this.startRun = startRun;
    this.getRun = getRun;
    this.recovery = recovery;
  }

  @PostMapping("/projects")
  ResponseEntity<ProjectView> register(@RequestBody RegisterProjectRequest request) {
    var result = ProjectView.from(registerProject.register(request.source(), request.revision()));
    return ResponseEntity.created(URI.create("/api/v1/projects/" + result.id())).body(result);
  }

  @PostMapping("/projects/preflight")
  ProjectPreflightView preflight(@RequestBody RegisterProjectRequest request) {
    var result = inspectProject.inspect(request.source(), request.revision());
    return ProjectPreflightView.from(result);
  }

  @PostMapping(
      value = "/projects/preflight-directory",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ProjectPreflightView preflightDirectory(
      @RequestParam String directoryName,
      @RequestParam("paths") List<String> paths,
      @RequestPart("files") List<MultipartFile> files) {
    if (paths.size() != files.size()) {
      throw new IllegalArgumentException("Every uploaded file must have one relative path");
    }
    var snapshotFiles = new java.util.ArrayList<DirectorySnapshotFile>(files.size());
    for (int index = 0; index < files.size(); index++) {
      try {
        snapshotFiles.add(new DirectorySnapshotFile(paths.get(index), files.get(index).getBytes()));
      } catch (IOException exception) {
        throw new IllegalArgumentException("Unable to read uploaded directory file", exception);
      }
    }
    var result = inspectProject.inspectDirectory(directoryName, snapshotFiles);
    return ProjectPreflightView.from(result);
  }

  private static ProjectPreflightView preflightView(
      io.legacypilot.application.port.RepositoryInspection result) {
    return new ProjectPreflightView(
        result.source(),
        result.repositoryPath(),
        result.revision().value(),
        result.sourceKind(),
        result.clean(),
        "NOT_RUN",
        result.technologies(),
        result.capabilities(),
        result.warnings());
  }

  @GetMapping("/projects/{projectId}")
  ProjectView project(@PathVariable String projectId) {
    return ProjectView.from(getProject.get(projectId));
  }

  @GetMapping("/projects")
  List<ProjectView> projects() {
    return listProjects.list().stream().map(ProjectView::from).toList();
  }

  @DeleteMapping("/projects/{projectId}")
  ResponseEntity<Void> removeProject(@PathVariable String projectId) {
    removeProject.remove(projectId);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/projects/{projectId}/tasks")
  List<TaskSummaryView> tasks(@PathVariable String projectId) {
    return listProjectTasks.list(projectId).stream()
        .map(
            task ->
                TaskSummaryView.from(task, getRun.latestForTask(task.id().value()).orElse(null)))
        .toList();
  }

  @PostMapping("/projects/{projectId}/tasks")
  ResponseEntity<TaskView> createTask(
      @PathVariable String projectId, @RequestBody CreateTaskRequest request) {
    var result =
        TaskView.from(
            createTask.create(
                projectId,
                request.mode(),
                request.requirement(),
                request.criteria(),
                request.conversationId()));
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
  RunView cancel(
      @PathVariable String runId,
      @org.springframework.web.bind.annotation.RequestHeader(value = "If-Match", required = false)
          Long expectedVersion,
      @RequestBody(required = false) CancelRunRequest request,
      org.springframework.security.core.Authentication authentication) {
    if (expectedVersion == null) {
      recovery.inspect(runId);
      throw new IllegalArgumentException("If-Match run version is required");
    }
    return RunView.from(
        recovery.cancel(
            runId,
            expectedVersion,
            authentication.getName(),
            request == null ? null : request.reason()));
  }

  public record CancelRunRequest(String reason) {}

  public record RegisterProjectRequest(String source, String revision) {}

  public record ProjectPreflightView(
      String source,
      String repositoryPath,
      String resolvedRevision,
      String sourceKind,
      boolean clean,
      String compilation,
      List<String> technologies,
      List<String> capabilities,
      List<String> warnings) {
    public ProjectPreflightView {
      technologies = List.copyOf(technologies);
      capabilities = List.copyOf(capabilities);
      warnings = List.copyOf(warnings);
    }

    static ProjectPreflightView from(io.legacypilot.application.port.RepositoryInspection result) {
      return preflightView(result);
    }
  }

  public record CreateTaskRequest(
      TaskMode mode, String requirement, List<String> criteria, String conversationId) {
    public CreateTaskRequest {
      mode = mode == null ? TaskMode.CHANGE : mode;
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
      String id,
      String projectId,
      String conversationId,
      TaskMode mode,
      String requirement,
      List<String> criteria,
      Instant createdAt) {
    public TaskView {
      criteria = List.copyOf(criteria);
    }

    static TaskView from(Task task) {
      return new TaskView(
          task.id().value(),
          task.projectId().value(),
          task.conversationId(),
          task.mode(),
          task.requirement().text(),
          task.acceptanceCriteria().items(),
          task.createdAt());
    }
  }

  public record TaskSummaryView(
      String id,
      String projectId,
      String conversationId,
      TaskMode mode,
      String requirement,
      List<String> criteria,
      Instant createdAt,
      RunView latestRun) {
    public TaskSummaryView {
      criteria = List.copyOf(criteria);
    }

    @Override
    public List<String> criteria() {
      return List.copyOf(criteria);
    }

    static TaskSummaryView from(Task value, TaskRun latestRun) {
      var task = TaskView.from(value);
      return new TaskSummaryView(
          task.id(),
          task.projectId(),
          task.conversationId(),
          task.mode(),
          task.requirement(),
          task.criteria(),
          task.createdAt(),
          latestRun == null ? null : RunView.from(latestRun));
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

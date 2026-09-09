package io.legacypilot.application.service;

import io.legacypilot.application.error.ResourceNotFoundException;
import io.legacypilot.application.port.IdGenerator;
import io.legacypilot.application.port.ProjectRepository;
import io.legacypilot.application.port.TaskRepository;
import io.legacypilot.domain.project.ProjectId;
import io.legacypilot.domain.task.AcceptanceCriteria;
import io.legacypilot.domain.task.Requirement;
import io.legacypilot.domain.task.Task;
import io.legacypilot.domain.task.TaskId;
import io.legacypilot.domain.task.TaskMode;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

public final class CreateTaskUseCase {

  private final ProjectRepository projects;
  private final TaskRepository tasks;
  private final IdGenerator ids;
  private final Clock clock;

  public CreateTaskUseCase(
      ProjectRepository projects, TaskRepository tasks, IdGenerator ids, Clock clock) {
    this.projects = Objects.requireNonNull(projects);
    this.tasks = Objects.requireNonNull(tasks);
    this.ids = Objects.requireNonNull(ids);
    this.clock = Objects.requireNonNull(clock);
  }

  public Task create(String projectIdValue, String requirement, List<String> criteria) {
    return create(projectIdValue, TaskMode.CHANGE, requirement, criteria, null);
  }

  public Task create(
      String projectIdValue, TaskMode mode, String requirement, List<String> criteria) {
    return create(projectIdValue, mode, requirement, criteria, null);
  }

  public Task create(
      String projectIdValue,
      TaskMode mode,
      String requirement,
      List<String> criteria,
      String conversationId) {
    var projectId = new ProjectId(projectIdValue);
    if (projects.findById(projectId).isEmpty()) {
      throw new ResourceNotFoundException("Project", projectIdValue);
    }
    var taskId = new TaskId(ids.next("task"));
    var resolvedConversationId = resolveConversation(projectId, taskId, conversationId);
    var task =
        new Task(
            taskId,
            projectId,
            resolvedConversationId,
            Objects.requireNonNull(mode, "task mode is required"),
            new Requirement(requirement),
            new AcceptanceCriteria(criteria == null ? List.of() : criteria),
            clock.instant());
    tasks.add(task);
    return task;
  }

  private String resolveConversation(
      ProjectId projectId, TaskId taskId, String requestedConversationId) {
    if (requestedConversationId == null || requestedConversationId.isBlank()) {
      return taskId.value();
    }
    var root =
        tasks
            .findById(new TaskId(requestedConversationId))
            .orElseThrow(
                () -> new ResourceNotFoundException("Conversation", requestedConversationId));
    if (!root.projectId().equals(projectId)) {
      throw new IllegalArgumentException("conversation must belong to the same project");
    }
    return root.conversationId();
  }
}

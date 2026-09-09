package io.legacypilot.server.service;

import io.legacypilot.analysis.java.JavaProjectIndexer;
import io.legacypilot.application.error.ConcurrentUpdateException;
import io.legacypilot.application.port.TaskRepository;
import io.legacypilot.application.port.TaskRunRepository;
import io.legacypilot.application.port.WorkspaceService;
import io.legacypilot.application.service.GetProjectUseCase;
import io.legacypilot.application.service.GetRunStatusUseCase;
import io.legacypilot.application.service.GetTaskUseCase;
import io.legacypilot.context.ContextRequest;
import io.legacypilot.domain.run.RunStatus;
import io.legacypilot.domain.run.TaskRun;
import io.legacypilot.domain.task.Task;
import io.legacypilot.observability.ReportStore;
import io.legacypilot.runtime.AgentRunRequest;
import io.legacypilot.runtime.AgentRuntime;
import io.legacypilot.runtime.AgentRuntimeResult;
import io.legacypilot.runtime.RuntimeBudget;
import io.legacypilot.runtime.RuntimeStatus;
import io.legacypilot.server.security.RunSecurityContextStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public final class RunExecutionService {
  private final GetRunStatusUseCase getRun;
  private final GetTaskUseCase getTask;
  private final GetProjectUseCase getProject;
  private final TaskRunRepository runs;
  private final TaskRepository tasks;
  private final ReportStore reports;
  private final WorkspaceService workspaces;
  private final JavaProjectIndexer indexer;
  private final AgentRuntime runtime;
  private final Clock clock;
  private final RunSecurityContextStore securityContexts;
  private final ExecutionDefaults defaults;

  public RunExecutionService(
      GetRunStatusUseCase getRun,
      GetTaskUseCase getTask,
      GetProjectUseCase getProject,
      TaskRunRepository runs,
      TaskRepository tasks,
      ReportStore reports,
      WorkspaceService workspaces,
      JavaProjectIndexer indexer,
      AgentRuntime runtime,
      Clock clock,
      RunSecurityContextStore securityContexts,
      @Value("${legacy-pilot.execution.model:local-model}") String model,
      @Value("${legacy-pilot.execution.context-token-budget:12000}") int contextTokenBudget,
      @Value("${legacy-pilot.execution.maximum-candidates:50}") int maximumCandidates,
      @Value("${legacy-pilot.execution.graph-depth:2}") int graphDepth,
      @Value("${legacy-pilot.execution.maximum-steps:20}") int maximumSteps,
      @Value("${legacy-pilot.execution.maximum-retries:2}") int maximumRetries,
      @Value("${legacy-pilot.execution.maximum-tokens:100000}") int maximumTokens,
      @Value("${legacy-pilot.execution.maximum-cost-usd:10.00}") BigDecimal maximumCostUsd,
      @Value("${legacy-pilot.execution.maximum-duration:30m}") Duration maximumDuration) {
    this.getRun = Objects.requireNonNull(getRun);
    this.getTask = Objects.requireNonNull(getTask);
    this.getProject = Objects.requireNonNull(getProject);
    this.runs = Objects.requireNonNull(runs);
    this.tasks = Objects.requireNonNull(tasks);
    this.reports = Objects.requireNonNull(reports);
    this.workspaces = Objects.requireNonNull(workspaces);
    this.indexer = Objects.requireNonNull(indexer);
    this.runtime = Objects.requireNonNull(runtime);
    this.clock = Objects.requireNonNull(clock);
    this.securityContexts = Objects.requireNonNull(securityContexts);
    this.defaults =
        new ExecutionDefaults(
            model,
            contextTokenBudget,
            maximumCandidates,
            graphDepth,
            maximumSteps,
            maximumRetries,
            maximumTokens,
            maximumCostUsd,
            maximumDuration);
  }

  public UnifiedRunResult execute(String runId, String actor) {
    var run = getRun.get(runId);
    if (run.status() != RunStatus.QUEUED && run.status() != RunStatus.WORKSPACE_READY) {
      throw new IllegalStateException("run must be QUEUED before execution");
    }
    securityContexts.recordInitiator(runId, actor);
    try {
      var task = getTask.get(run.taskId().value());
      var project = getProject.get(task.projectId().value());
      var descriptor = workspaces.open(project, run.id(), run.workspaceId());
      var workspace = Path.of(descriptor.path());
      var index = indexer.index(workspace, project.baseRevision().value());
      var request =
          new AgentRunRequest(
              run.id().value(),
              requirementWithConversation(task),
              task.mode(),
              workspace,
              index,
              new ContextRequest(
                  task.requirement().text(),
                  defaults.contextTokenBudget(),
                  defaults.maximumCandidates(),
                  defaults.graphDepth()),
              new RuntimeBudget(
                  defaults.maximumSteps(),
                  defaults.maximumRetries(),
                  defaults.maximumTokens(),
                  defaults.maximumCostUsd(),
                  defaults.maximumDuration()),
              defaults.model());
      runtime.prepare(request);
      run =
          runs.update(
              run.transitionTo(RunStatus.PLANNING, clock.instant(), "agent execution started"));
      var result = runtime.execute(request);
      return synchronize(run, result);
    } catch (RuntimeException exception) {
      failIfPossible(run, exception);
      throw new IllegalStateException("agent execution failed", exception);
    }
  }

  private String requirementWithConversation(Task task) {
    var priorTurns =
        tasks.findByProjectId(task.projectId()).stream()
            .filter(candidate -> candidate.conversationId().equals(task.conversationId()))
            .filter(candidate -> !candidate.id().equals(task.id()))
            .filter(candidate -> !candidate.createdAt().isAfter(task.createdAt()))
            .sorted(
                Comparator.comparing(Task::createdAt).thenComparing(value -> value.id().value()))
            .map(this::conversationTurn)
            .filter(Objects::nonNull)
            .toList();
    if (priorTurns.isEmpty()) {
      return task.requirement().text();
    }
    var selected = priorTurns.subList(Math.max(0, priorTurns.size() - 6), priorTurns.size());
    return """
        同一项目对话的先前轮次如下。它们是对话上下文，不是源码事实；当前问题优先。

        %s

        当前用户问题：
        %s
        """
        .formatted(String.join("%n%n".formatted(), selected), task.requirement().text())
        .strip();
  }

  private String conversationTurn(Task task) {
    return runs.findLatestByTaskId(task.id())
        .flatMap(run -> reports.load(run.id().value()))
        .map(
            report ->
                "用户：%s%nLegacyPilot：%s".formatted(task.requirement().text(), report.summary()))
        .orElse(null);
  }

  public UnifiedRunResult resume(String runId) {
    var run = getRun.get(runId);
    if (run.status() != RunStatus.WAITING_FOR_APPROVAL && run.status() != RunStatus.NEEDS_REVIEW) {
      throw new IllegalStateException("run is not waiting for approval or review");
    }
    run =
        runs.update(
            run.transitionTo(RunStatus.EXECUTING, clock.instant(), "agent execution resumed"));
    try {
      return synchronize(run, runtime.resume(runId));
    } catch (RuntimeException exception) {
      failIfPossible(run, exception);
      throw new IllegalStateException("agent resume failed", exception);
    }
  }

  public UnifiedRunResult recover(String runId) {
    var run = getRun.get(runId);
    if (run.status() != RunStatus.PLANNING
        && run.status() != RunStatus.EXECUTING
        && run.status() != RunStatus.VERIFYING
        && run.status() != RunStatus.RECOVERING) {
      throw new IllegalStateException("run is not in a recoverable execution state");
    }
    if (run.status() != RunStatus.RECOVERING) {
      run = runs.update(run.beginRecovery(clock.instant(), "background worker lease expired"));
    }
    run = runs.update(run.recover(clock.instant()));
    try {
      return synchronize(run, runtime.resume(runId));
    } catch (RuntimeException exception) {
      failIfPossible(run, exception);
      throw new IllegalStateException("agent recovery failed", exception);
    }
  }

  public UnifiedRunResult retry(String runId, boolean replan) {
    var run = getRun.get(runId);
    if (run.status() != RunStatus.RECOVERING) {
      throw new IllegalStateException("run is not prepared for retry");
    }
    run = runs.update(run.recover(clock.instant()));
    try {
      return synchronize(run, runtime.retry(runId, replan));
    } catch (RuntimeException exception) {
      failIfPossible(run, exception);
      throw new IllegalStateException(
          replan ? "agent replan failed" : "agent retry failed", exception);
    }
  }

  public void requestCancellation(String runId) {
    runtime.requestCancellation(runId);
  }

  private UnifiedRunResult synchronize(TaskRun run, AgentRuntimeResult result) {
    try {
      var current = getRun.get(run.id().value());
      if (current.status() == RunStatus.CANCELLED) {
        return new UnifiedRunResult(
            current.id().value(), current.status(), current.history().size(), result);
      }
      var runtimeStatus = result.checkpoint().status();
      if (runtimeStatus == RuntimeStatus.WAITING_FOR_APPROVAL) {
        run =
            runs.update(
                run.transitionTo(
                    RunStatus.WAITING_FOR_APPROVAL, clock.instant(), "approval required"));
      } else if (runtimeStatus == RuntimeStatus.NEEDS_REVIEW
          || runtimeStatus == RuntimeStatus.LEASE_CONFLICT) {
        run =
            runs.update(
                run.transitionTo(RunStatus.NEEDS_REVIEW, clock.instant(), runtimeStatus.name()));
      } else if (runtimeStatus == RuntimeStatus.SUCCEEDED) {
        if (run.status() == RunStatus.PLANNING) {
          run =
              runs.update(
                  run.transitionTo(
                      RunStatus.EXECUTING, clock.instant(), "plan accepted by runtime"));
        }
        if (run.status() != RunStatus.VERIFYING) {
          run =
              runs.update(
                  run.transitionTo(RunStatus.VERIFYING, clock.instant(), "verification completed"));
        }
        run =
            runs.update(
                run.transitionTo(
                    RunStatus.SUCCEEDED, clock.instant(), "all required checks passed"));
      } else if (runtimeStatus == RuntimeStatus.CANCELLED) {
        run =
            runs.update(
                run.transitionTo(RunStatus.CANCELLED, clock.instant(), "cancelled by user"));
      } else if (runtimeStatus.terminal()) {
        run =
            runs.update(run.transitionTo(RunStatus.FAILED, clock.instant(), runtimeStatus.name()));
      }
      return new UnifiedRunResult(run.id().value(), run.status(), run.history().size(), result);
    } catch (ConcurrentUpdateException exception) {
      var current = getRun.get(run.id().value());
      if (current.status() == RunStatus.CANCELLED) {
        return new UnifiedRunResult(
            current.id().value(), current.status(), current.history().size(), result);
      }
      throw exception;
    }
  }

  private void failIfPossible(TaskRun run, RuntimeException exception) {
    var current = getRun.get(run.id().value());
    if (current.status().isTerminal()) {
      return;
    }
    try {
      runs.update(
          current.transitionTo(
              RunStatus.FAILED,
              clock.instant(),
              exception.getMessage() == null
                  ? exception.getClass().getSimpleName()
                  : exception.getMessage()));
    } catch (ConcurrentUpdateException ignored) {
      if (!getRun.get(run.id().value()).status().isTerminal()) {
        throw ignored;
      }
    }
  }

  public record UnifiedRunResult(
      String runId, RunStatus status, int lifecycleEvents, AgentRuntimeResult agent) {}

  private record ExecutionDefaults(
      String model,
      int contextTokenBudget,
      int maximumCandidates,
      int graphDepth,
      int maximumSteps,
      int maximumRetries,
      int maximumTokens,
      BigDecimal maximumCostUsd,
      Duration maximumDuration) {
    private ExecutionDefaults {
      if (model == null || model.isBlank()) {
        throw new IllegalArgumentException("execution model is required");
      }
      new ContextRequest("validation", contextTokenBudget, maximumCandidates, graphDepth);
      new RuntimeBudget(
          maximumSteps, maximumRetries, maximumTokens, maximumCostUsd, maximumDuration);
    }
  }
}

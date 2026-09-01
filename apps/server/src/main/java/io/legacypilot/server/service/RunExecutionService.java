package io.legacypilot.server.service;

import io.legacypilot.analysis.java.JavaProjectIndexer;
import io.legacypilot.application.port.TaskRunRepository;
import io.legacypilot.application.port.WorkspaceService;
import io.legacypilot.application.service.GetProjectUseCase;
import io.legacypilot.application.service.GetRunStatusUseCase;
import io.legacypilot.application.service.GetTaskUseCase;
import io.legacypilot.context.ContextRequest;
import io.legacypilot.domain.run.RunStatus;
import io.legacypilot.domain.run.TaskRun;
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
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public final class RunExecutionService {
  private final GetRunStatusUseCase getRun;
  private final GetTaskUseCase getTask;
  private final GetProjectUseCase getProject;
  private final TaskRunRepository runs;
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
    if (run.status() != RunStatus.WORKSPACE_READY) {
      throw new IllegalStateException("run must be WORKSPACE_READY before execution");
    }
    securityContexts.recordInitiator(runId, actor);
    run =
        runs.update(
            run.transitionTo(RunStatus.PLANNING, clock.instant(), "agent execution started"));
    try {
      var task = getTask.get(run.taskId().value());
      var project = getProject.get(task.projectId().value());
      var descriptor = workspaces.open(project, run.id(), run.workspaceId());
      var workspace = Path.of(descriptor.path());
      var index = indexer.index(workspace, project.baseRevision().value());
      var result =
          runtime.execute(
              new AgentRunRequest(
                  run.id().value(),
                  task.requirement().text(),
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
                  defaults.model()));
      return synchronize(run, result);
    } catch (RuntimeException exception) {
      failIfPossible(run, exception);
      throw new IllegalStateException("agent execution failed", exception);
    }
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

  private UnifiedRunResult synchronize(TaskRun run, AgentRuntimeResult result) {
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
                run.transitionTo(RunStatus.EXECUTING, clock.instant(), "plan accepted by runtime"));
      }
      run =
          runs.update(
              run.transitionTo(RunStatus.VERIFYING, clock.instant(), "verification completed"));
      run =
          runs.update(
              run.transitionTo(RunStatus.SUCCEEDED, clock.instant(), "all required checks passed"));
    } else if (runtimeStatus.terminal()) {
      run = runs.update(run.transitionTo(RunStatus.FAILED, clock.instant(), runtimeStatus.name()));
    }
    return new UnifiedRunResult(run.id().value(), run.status(), run.history().size(), result);
  }

  private void failIfPossible(TaskRun run, RuntimeException exception) {
    if (!run.status().isTerminal()) {
      runs.update(
          run.transitionTo(
              RunStatus.FAILED,
              clock.instant(),
              exception.getMessage() == null
                  ? exception.getClass().getSimpleName()
                  : exception.getMessage()));
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

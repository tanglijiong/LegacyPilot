package io.legacypilot.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.context.ContextBuilder;
import io.legacypilot.model.ModelException;
import io.legacypilot.model.ModelUsage;
import io.legacypilot.observability.AgentMetrics;
import io.legacypilot.observability.ReportStore;
import io.legacypilot.observability.RunReport;
import io.legacypilot.observability.TraceEvent;
import io.legacypilot.observability.TraceSink;
import io.legacypilot.tool.spi.ActionDigests;
import io.legacypilot.tool.spi.ToolContext;
import io.legacypilot.tool.spi.ToolExecutor;
import io.legacypilot.tool.spi.ToolStatus;
import io.legacypilot.verification.VerificationContext;
import io.legacypilot.verification.VerificationOutcome;
import io.legacypilot.verification.VerificationPipeline;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class AgentRuntime {

  private static final int MAXIMUM_IDENTICAL_FAILURES = 3;
  private final AgentPlanner planner;
  private final ContextBuilder contexts;
  private final ToolExecutor tools;
  private final VerificationPipeline verification;
  private final CheckpointStore checkpoints;
  private final AgentRunRequestStore requests;
  private final ApprovalStore approvals;
  private final TraceSink trace;
  private final ReportStore reports;
  private final AgentMetrics metrics;
  private final ObjectMapper mapper;
  private final Clock clock;

  public AgentRuntime(
      AgentPlanner planner,
      ContextBuilder contexts,
      ToolExecutor tools,
      VerificationPipeline verification,
      CheckpointStore checkpoints,
      ApprovalStore approvals,
      TraceSink trace,
      AgentMetrics metrics,
      ObjectMapper mapper,
      Clock clock) {
    this(
        planner,
        contexts,
        tools,
        verification,
        checkpoints,
        new InMemoryAgentRunRequestStore(),
        approvals,
        trace,
        new InMemoryReportStore(),
        metrics,
        mapper,
        clock);
  }

  public AgentRuntime(
      AgentPlanner planner,
      ContextBuilder contexts,
      ToolExecutor tools,
      VerificationPipeline verification,
      CheckpointStore checkpoints,
      AgentRunRequestStore requests,
      ApprovalStore approvals,
      TraceSink trace,
      ReportStore reports,
      AgentMetrics metrics,
      ObjectMapper mapper,
      Clock clock) {
    this.planner = Objects.requireNonNull(planner);
    this.contexts = Objects.requireNonNull(contexts);
    this.tools = Objects.requireNonNull(tools);
    this.verification = Objects.requireNonNull(verification);
    this.checkpoints = Objects.requireNonNull(checkpoints);
    this.requests = Objects.requireNonNull(requests);
    this.approvals = Objects.requireNonNull(approvals);
    this.trace = Objects.requireNonNull(trace);
    this.reports = Objects.requireNonNull(reports);
    this.metrics = Objects.requireNonNull(metrics);
    this.mapper = Objects.requireNonNull(mapper);
    this.clock = Objects.requireNonNull(clock);
  }

  public AgentRuntimeResult execute(AgentRunRequest request) {
    requests.save(request);
    try {
      return run(request);
    } catch (ModelException exception) {
      var checkpoint = checkpoints.load(request.runId()).orElseThrow(() -> exception);
      event(
          checkpoint,
          "model.failed",
          Map.of(
              "type",
              exception.type().name(),
              "retryable",
              Boolean.toString(exception.retryable())));
      if (exception.retryable() && checkpoint.retries() < request.budget().maximumRetries()) {
        update(
            checkpoint,
            RuntimeStatus.EXECUTING,
            checkpoint.plan(),
            checkpoint.steps(),
            checkpoint.retries() + 1,
            checkpoint.usage(),
            checkpoint.pendingAction(),
            checkpoint.lastFailedDigest(),
            checkpoint.repeatedFailures(),
            "retrying transient model failure: " + exception.type().name());
        return execute(request);
      }
      checkpoint =
          terminal(checkpoint, RuntimeStatus.FAILED, "model failure: " + exception.type().name());
      return result(checkpoint, null);
    }
  }

  public AgentRuntimeResult resume(String runId) {
    var request =
        requests
            .load(runId)
            .orElseThrow(() -> new IllegalArgumentException("agent run request was not found"));
    return execute(request);
  }

  private AgentRuntimeResult run(AgentRunRequest request) {
    var checkpoint =
        checkpoints
            .load(request.runId())
            .orElseGet(() -> initial(request.runId(), clock.instant()));
    if (checkpoint.status().terminal()) {
      return result(checkpoint, null);
    }
    if (checkpoint.plan() == null) {
      var context = contexts.build(request.projectIndex(), request.contextRequest());
      var planned = planner.plan(request, context);
      metrics.modelUsage(planned.usage().totalTokens());
      checkpoint =
          update(
              checkpoint,
              RuntimeStatus.EXECUTING,
              planned.value(),
              checkpoint.steps(),
              checkpoint.retries(),
              checkpoint.usage().plus(planned.usage()),
              null,
              "",
              0,
              "plan created");
      event(checkpoint, "plan.created", Map.of("risk", checkpoint.plan().risk()));
    }
    while (true) {
      var exhausted = exhausted(checkpoint, request.budget());
      if (exhausted != null) {
        checkpoint = terminal(checkpoint, RuntimeStatus.BUDGET_EXHAUSTED, exhausted);
        return result(checkpoint, null);
      }
      AgentAction action;
      if (checkpoint.pendingAction() != null) {
        action = checkpoint.pendingAction();
      } else {
        var decision =
            planner.next(
                request, checkpoint.plan(), checkpoint.observation(), checkpoint.steps() + 1);
        checkpoint = usage(checkpoint, decision.usage());
        action = decision.value();
      }
      if (action.type().equals("VERIFY") || action.type().equals("FINISH")) {
        return verify(request, checkpoint);
      }
      var outcome = executeTool(request, checkpoint, action);
      checkpoint = outcome.checkpoint();
      if (outcome.pausedOrTerminal()) {
        return result(checkpoint, null);
      }
    }
  }

  private ToolOutcome executeTool(
      AgentRunRequest request, AgentCheckpoint checkpoint, AgentAction action) {
    var digest = ActionDigests.create(action.tool(), action.input());
    var planDigest = ActionDigests.create("change_plan", mapper.valueToTree(checkpoint.plan()));
    var approval =
        approvals
            .consumeMatching(request.runId(), digest, planDigest, clock.instant())
            .orElse(null);
    if (approval != null && approval.decision() == RuntimeApproval.Decision.DENIED) {
      var denied =
          terminal(checkpoint, RuntimeStatus.DENIED, "action denied by " + approval.actor());
      event(denied, "approval.denied", Map.of("actor", approval.actor(), "digest", digest));
      return new ToolOutcome(denied, true);
    }
    if (approval != null) {
      event(
          checkpoint,
          "approval.consumed",
          Map.of(
              "actor", approval.actor(),
              "digest", digest,
              "scope", approval.scope().name()));
    }
    var approved = approval == null ? Set.<String>of() : Set.of(digest);
    var context = new ToolContext(request.runId(), request.workspace(), approved, false);
    var result = tools.execute(action.tool(), context, action.input());
    metrics.toolInvocation(action.tool(), result.status().name());
    event(
        checkpoint,
        "tool.completed",
        Map.of("tool", action.tool(), "status", result.status().name(), "digest", digest));
    if (result.status() == ToolStatus.APPROVAL_REQUIRED) {
      var paused =
          update(
              checkpoint,
              RuntimeStatus.WAITING_FOR_APPROVAL,
              checkpoint.plan(),
              checkpoint.steps(),
              checkpoint.retries(),
              checkpoint.usage(),
              action,
              checkpoint.lastFailedDigest(),
              checkpoint.repeatedFailures(),
              "approval required: " + result.actionDigest());
      return new ToolOutcome(paused, true);
    }
    var steps = checkpoint.steps() + 1;
    if (!result.successful()) {
      var repeated =
          digest.equals(checkpoint.lastFailedDigest()) ? checkpoint.repeatedFailures() + 1 : 1;
      var retries = checkpoint.retries() + 1;
      if (repeated >= MAXIMUM_IDENTICAL_FAILURES) {
        return new ToolOutcome(
            terminal(checkpoint, RuntimeStatus.FAILED, "repeated failed action detected"), true);
      }
      var failed =
          update(
              checkpoint,
              RuntimeStatus.EXECUTING,
              checkpoint.plan(),
              steps,
              retries,
              checkpoint.usage(),
              null,
              digest,
              repeated,
              result.error().code() + ": " + result.error().message());
      return new ToolOutcome(failed, false);
    }
    var observation = result.output() == null ? "tool succeeded" : result.output().toString();
    return new ToolOutcome(
        update(
            checkpoint,
            RuntimeStatus.EXECUTING,
            checkpoint.plan(),
            steps,
            checkpoint.retries(),
            checkpoint.usage(),
            null,
            "",
            0,
            observation),
        false);
  }

  private AgentRuntimeResult verify(AgentRunRequest request, AgentCheckpoint checkpoint) {
    checkpoint =
        update(
            checkpoint,
            RuntimeStatus.VERIFYING,
            checkpoint.plan(),
            checkpoint.steps(),
            checkpoint.retries(),
            checkpoint.usage(),
            null,
            checkpoint.lastFailedDigest(),
            checkpoint.repeatedFailures(),
            "verification started");
    var context =
        new VerificationContext(
            request.workspace(),
            new ToolContext(request.runId(), request.workspace(), Set.of(), true),
            tools);
    var outcome = verification.verify(context);
    event(
        checkpoint,
        "verification.completed",
        Map.of("success", Boolean.toString(outcome.successful()), "risk", outcome.risk().name()));
    if (outcome.successful()) {
      checkpoint = terminal(checkpoint, RuntimeStatus.SUCCEEDED, "required checks passed");
      return result(checkpoint, outcome);
    }
    if (outcome.repairable() && checkpoint.retries() < request.budget().maximumRetries()) {
      update(
          checkpoint,
          RuntimeStatus.EXECUTING,
          checkpoint.plan(),
          checkpoint.steps(),
          checkpoint.retries() + 1,
          checkpoint.usage(),
          null,
          "",
          0,
          outcome.repairFeedback());
      return execute(request);
    }
    checkpoint = terminal(checkpoint, RuntimeStatus.FAILED, "required verification failed");
    return result(checkpoint, outcome);
  }

  private AgentCheckpoint initial(String runId, Instant now) {
    var checkpoint =
        new AgentCheckpoint(
            runId, RuntimeStatus.PLANNING, null, 0, 0, ModelUsage.NONE, now, now, null, "", 0, "");
    checkpoints.save(checkpoint);
    event(checkpoint, "run.started", Map.of());
    return checkpoint;
  }

  private AgentCheckpoint usage(AgentCheckpoint checkpoint, ModelUsage usage) {
    metrics.modelUsage(usage.totalTokens());
    return update(
        checkpoint,
        checkpoint.status(),
        checkpoint.plan(),
        checkpoint.steps(),
        checkpoint.retries(),
        checkpoint.usage().plus(usage),
        checkpoint.pendingAction(),
        checkpoint.lastFailedDigest(),
        checkpoint.repeatedFailures(),
        checkpoint.observation());
  }

  private String exhausted(AgentCheckpoint checkpoint, RuntimeBudget budget) {
    if (checkpoint.steps() >= budget.maximumSteps()) {
      return "step budget exhausted";
    }
    if (checkpoint.retries() > budget.maximumRetries()) {
      return "retry budget exhausted";
    }
    if (checkpoint.usage().totalTokens() > budget.maximumTokens()) {
      return "token budget exhausted";
    }
    if (checkpoint.usage().estimatedCostUsd().compareTo(budget.maximumCostUsd()) > 0) {
      return "cost budget exhausted";
    }
    if (Duration.between(checkpoint.startedAt(), clock.instant())
            .compareTo(budget.maximumDuration())
        > 0) {
      return "duration budget exhausted";
    }
    return null;
  }

  private AgentCheckpoint terminal(
      AgentCheckpoint checkpoint, RuntimeStatus status, String observation) {
    var value =
        update(
            checkpoint,
            status,
            checkpoint.plan(),
            checkpoint.steps(),
            checkpoint.retries(),
            checkpoint.usage(),
            null,
            checkpoint.lastFailedDigest(),
            checkpoint.repeatedFailures(),
            observation);
    metrics.runCompleted(status.name(), Duration.between(value.startedAt(), value.updatedAt()));
    event(value, "run.completed", Map.of("status", status.name(), "reason", observation));
    return value;
  }

  private AgentCheckpoint update(
      AgentCheckpoint old,
      RuntimeStatus status,
      ChangePlan plan,
      int steps,
      int retries,
      ModelUsage usage,
      AgentAction pending,
      String failedDigest,
      int repeated,
      String observation) {
    var value =
        new AgentCheckpoint(
            old.runId(),
            status,
            plan,
            steps,
            retries,
            usage,
            old.startedAt(),
            clock.instant(),
            pending,
            failedDigest,
            repeated,
            observation);
    checkpoints.save(value);
    return value;
  }

  private void event(AgentCheckpoint checkpoint, String type, Map<String, String> attributes) {
    var sequence = trace.events(checkpoint.runId()).size() + 1;
    trace.append(new TraceEvent(checkpoint.runId(), sequence, type, clock.instant(), attributes));
  }

  private RunReport report(AgentCheckpoint checkpoint, VerificationOutcome verificationOutcome) {
    var evidence = new ArrayList<Map<String, String>>();
    var risk = "UNKNOWN";
    if (verificationOutcome != null) {
      risk = verificationOutcome.risk().name();
      verificationOutcome
          .evidence()
          .forEach(
              item ->
                  evidence.add(
                      Map.of(
                          "name", item.name(),
                          "status", item.status().name(),
                          "evidence", item.summary())));
    }
    return new RunReport(
        checkpoint.runId(),
        checkpoint.status().name(),
        checkpoint.observation(),
        checkpoint.steps(),
        checkpoint.usage().totalTokens(),
        checkpoint.usage().estimatedCostUsd(),
        Duration.between(checkpoint.startedAt(), checkpoint.updatedAt()),
        risk,
        checkpoint.plan() == null ? List.of() : checkpoint.plan().steps(),
        evidence,
        trace.events(checkpoint.runId()));
  }

  private AgentRuntimeResult result(
      AgentCheckpoint checkpoint, VerificationOutcome verificationOutcome) {
    var report = report(checkpoint, verificationOutcome);
    reports.save(report);
    return new AgentRuntimeResult(checkpoint, verificationOutcome, report);
  }

  private static final class InMemoryReportStore implements ReportStore {
    private final java.util.concurrent.ConcurrentHashMap<String, RunReport> values =
        new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void save(RunReport report) {
      values.put(report.runId(), report);
    }

    @Override
    public java.util.Optional<RunReport> load(String runId) {
      return java.util.Optional.ofNullable(values.get(runId));
    }
  }

  private record ToolOutcome(AgentCheckpoint checkpoint, boolean pausedOrTerminal) {}
}

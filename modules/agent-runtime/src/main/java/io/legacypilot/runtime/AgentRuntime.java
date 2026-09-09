package io.legacypilot.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.context.ContextBuilder;
import io.legacypilot.context.ContextCompactor;
import io.legacypilot.context.InMemoryTaskMemoryStore;
import io.legacypilot.context.MemoryKind;
import io.legacypilot.context.TaskMemory;
import io.legacypilot.context.TaskMemoryStore;
import io.legacypilot.context.TokenEstimator;
import io.legacypilot.domain.task.TaskMode;
import io.legacypilot.model.ModelException;
import io.legacypilot.model.ModelUsage;
import io.legacypilot.observability.AgentMetrics;
import io.legacypilot.observability.ReportStore;
import io.legacypilot.observability.RunReport;
import io.legacypilot.observability.TaskArtifact;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class AgentRuntime {

  private static final int MAXIMUM_IDENTICAL_FAILURES =
      Math.max(3, Integer.getInteger("legacypilot.maximum-identical-failures", 3));
  private static final AtomicInteger LEASE_HEARTBEAT_THREAD_SEQUENCE = new AtomicInteger();
  private static final ScheduledExecutorService LEASE_HEARTBEATS =
      Executors.newScheduledThreadPool(
          1,
          runnable -> {
            var thread =
                new Thread(
                    runnable,
                    "agent-lease-heartbeat-" + LEASE_HEARTBEAT_THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
          });
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
  private final ActionJournal journal;
  private final RunLeaseStore leases;
  private final TaskMemoryStore memories;
  private final ContextCompactor compactor;
  private final String owner;
  private final Duration leaseTtl;
  private final ReadOnlyArtifactVerifier readOnlyVerifier;
  private final ConcurrentHashMap<String, RunLease> activeLeases = new ConcurrentHashMap<>();
  private final Set<String> cancellationRequests = ConcurrentHashMap.newKeySet();

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
        clock,
        new InMemoryActionJournal(),
        new InMemoryRunLeaseStore(),
        new InMemoryTaskMemoryStore(1_000),
        new ContextCompactor(TokenEstimator.conservative()),
        "runtime-" + UUID.randomUUID(),
        Duration.ofMinutes(2));
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
    this(
        planner,
        contexts,
        tools,
        verification,
        checkpoints,
        requests,
        approvals,
        trace,
        reports,
        metrics,
        mapper,
        clock,
        new InMemoryActionJournal(),
        new InMemoryRunLeaseStore(),
        new InMemoryTaskMemoryStore(1_000),
        new ContextCompactor(TokenEstimator.conservative()),
        "runtime-" + UUID.randomUUID(),
        Duration.ofMinutes(2));
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
      Clock clock,
      ActionJournal journal,
      RunLeaseStore leases,
      TaskMemoryStore memories,
      ContextCompactor compactor,
      String owner,
      Duration leaseTtl) {
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
    this.journal = Objects.requireNonNull(journal);
    this.leases = Objects.requireNonNull(leases);
    this.memories = Objects.requireNonNull(memories);
    this.compactor = Objects.requireNonNull(compactor);
    if (owner == null
        || owner.isBlank()
        || leaseTtl == null
        || leaseTtl.isNegative()
        || leaseTtl.isZero()) {
      throw new IllegalArgumentException("runtime owner and lease TTL are required");
    }
    this.owner = owner;
    this.leaseTtl = leaseTtl;
    this.readOnlyVerifier = new ReadOnlyArtifactVerifier(mapper);
  }

  public AgentRuntimeResult execute(AgentRunRequest request) {
    var lease = leases.acquire(request.runId(), owner, clock.instant(), leaseTtl).orElse(null);
    if (lease == null) {
      return leaseConflict(request.runId());
    }
    activeLeases.put(request.runId(), lease);
    var heartbeatDelayMillis = Math.max(1, leaseTtl.toMillis() / 3);
    ScheduledFuture<?> heartbeat =
        LEASE_HEARTBEATS.scheduleWithFixedDelay(
            () -> heartbeat(request.runId()),
            heartbeatDelayMillis,
            heartbeatDelayMillis,
            TimeUnit.MILLISECONDS);
    try {
      if (cancellationRequests.contains(request.runId())) {
        return cancelled(request.runId());
      }
      requests.save(request);
      trace.record(
          request.runId(),
          "lease.acquired",
          clock.instant(),
          Map.of(
              "owner", lease.owner(),
              "epoch", Long.toString(lease.epoch()),
              "expiresAt", lease.expiresAt().toString()));
      return run(request);
    } catch (ModelException exception) {
      var checkpoint = checkpoints.load(request.runId()).orElseThrow(() -> exception);
      if (cancellationRequests.contains(request.runId())) {
        return result(terminal(checkpoint, RuntimeStatus.CANCELLED, "cancelled by user"), null);
      }
      event(
          checkpoint,
          "model.failed",
          Map.of(
              "type",
              exception.type().name(),
              "retryable",
              Boolean.toString(exception.retryable()),
              "reason",
              exception.getMessage()));
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
          terminal(
              checkpoint,
              RuntimeStatus.FAILED,
              "model failure: " + exception.type().name() + " — " + exception.getMessage());
      return result(checkpoint, null);
    } finally {
      heartbeat.cancel(false);
      var current = activeLeases.remove(request.runId());
      if (current != null) {
        try {
          trace.record(
              request.runId(),
              "lease.released",
              clock.instant(),
              Map.of("owner", current.owner(), "epoch", Long.toString(current.epoch())));
        } finally {
          leases.release(current);
        }
      }
    }
  }

  /** Persists the complete request before an external dispatcher marks the run as active. */
  public void prepare(AgentRunRequest request) {
    requests.save(Objects.requireNonNull(request));
  }

  public AgentRuntimeResult resume(String runId) {
    var request =
        requests
            .load(runId)
            .orElseThrow(() -> new IllegalArgumentException("agent run request was not found"));
    return execute(request);
  }

  /** Requests cooperative cancellation at the next safe checkpoint. */
  public void requestCancellation(String runId) {
    cancellationRequests.add(runId);
    checkpoints
        .load(runId)
        .filter(
            checkpoint ->
                checkpoint.status() == RuntimeStatus.WAITING_FOR_APPROVAL
                    || checkpoint.status() == RuntimeStatus.NEEDS_REVIEW)
        .ifPresent(
            checkpoint -> terminal(checkpoint, RuntimeStatus.CANCELLED, "cancelled by user"));
  }

  /** Reopens a terminal checkpoint for an explicit retry or full replan. */
  public AgentRuntimeResult retry(String runId, boolean replan) {
    var checkpoint =
        checkpoints
            .load(runId)
            .orElseThrow(() -> new IllegalArgumentException("agent checkpoint was not found"));
    var replannablePause =
        replan
            && (checkpoint.status() == RuntimeStatus.WAITING_FOR_APPROVAL
                || checkpoint.status() == RuntimeStatus.NEEDS_REVIEW);
    if ((!checkpoint.status().terminal() && !replannablePause)
        || checkpoint.status() == RuntimeStatus.SUCCEEDED) {
      throw new IllegalStateException("only an unsuccessful terminal checkpoint can be retried");
    }
    cancellationRequests.remove(runId);
    if (replan) {
      memories.delete(runId);
    }
    var reset =
        new AgentCheckpoint(
            runId,
            replan ? RuntimeStatus.PLANNING : RuntimeStatus.EXECUTING,
            replan ? null : checkpoint.plan(),
            0,
            0,
            ModelUsage.NONE,
            clock.instant(),
            clock.instant(),
            null,
            "",
            0,
            replan ? "explicit replan requested" : "retrying from safe checkpoint");
    checkpoints.save(reset);
    event(
        reset,
        replan ? "attempt.replanned" : "attempt.retried",
        Map.of("previousStatus", checkpoint.status().name()));
    return resume(runId);
  }

  private AgentRuntimeResult run(AgentRunRequest request) {
    var checkpoint =
        checkpoints
            .load(request.runId())
            .orElseGet(() -> initial(request.runId(), clock.instant()));
    if (checkpoint.status().terminal()) {
      return result(checkpoint, null);
    }
    if (cancellationRequests.contains(request.runId())) {
      return result(terminal(checkpoint, RuntimeStatus.CANCELLED, "cancelled by user"), null);
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
        var compacted =
            compactor.compact(
                memories.active(request.runId(), clock.instant()),
                request.contextRequest().tokenBudget(),
                checkpoint.steps() + 1);
        event(
            checkpoint,
            "context.compacted",
            Map.of(
                "version",
                Integer.toString(compacted.version()),
                "tokens",
                Integer.toString(compacted.estimatedTokens()),
                "retained",
                Integer.toString(compacted.retainedMemoryIds().size())));
        var observation =
            compacted.content().isBlank()
                ? checkpoint.observation()
                : "Long-term task context:\n" + compacted.content();
        var actionContextRequest =
            new io.legacypilot.context.ContextRequest(
                String.join(" ", checkpoint.plan().affectedFiles()),
                Math.min(800, request.contextRequest().tokenBudget()),
                request.contextRequest().maximumCandidates(),
                Math.min(1, request.contextRequest().graphDepth()));
        var actionContext = contexts.build(request.projectIndex(), actionContextRequest);
        var decision =
            planner.next(
                request, checkpoint.plan(), observation, checkpoint.steps() + 1, actionContext);
        checkpoint = usage(checkpoint, decision.usage());
        action = normalizeNewFilePatch(decision.value());
      }
      if (action.type().equals("VERIFY") || action.type().equals("FINISH")) {
        if (cancellationRequests.contains(request.runId())) {
          return result(terminal(checkpoint, RuntimeStatus.CANCELLED, "cancelled by user"), null);
        }
        if (request.mode().readOnly()) {
          checkpoint =
              update(
                  checkpoint,
                  RuntimeStatus.EXECUTING,
                  checkpoint.plan(),
                  checkpoint.steps() + 1,
                  checkpoint.retries() + 1,
                  checkpoint.usage(),
                  null,
                  "",
                  0,
                  "Read-only tasks must submit a structured result before finishing");
          continue;
        }
        return verify(request, checkpoint);
      }
      if (action.type().equals("SUBMIT_RESULT")) {
        if (!request.mode().readOnly()) {
          checkpoint =
              terminal(
                  checkpoint,
                  RuntimeStatus.FAILED,
                  "CHANGE tasks cannot submit a read-only result");
          return result(checkpoint, null);
        }
        if (checkpoint.pendingAction() == null) {
          checkpoint =
              update(
                  checkpoint,
                  RuntimeStatus.EXECUTING,
                  checkpoint.plan(),
                  checkpoint.steps(),
                  checkpoint.retries(),
                  checkpoint.usage(),
                  action,
                  checkpoint.lastFailedDigest(),
                  checkpoint.repeatedFailures(),
                  "structured result submitted");
        }
        try {
          return verifyReadOnly(
              request, checkpoint, readOnlyVerifier.parse(request, action.input()));
        } catch (IllegalArgumentException exception) {
          checkpoint =
              update(
                  checkpoint,
                  RuntimeStatus.EXECUTING,
                  checkpoint.plan(),
                  checkpoint.steps() + 1,
                  checkpoint.retries() + 1,
                  checkpoint.usage(),
                  null,
                  "",
                  0,
                  "Submitted result is invalid: " + exception.getMessage());
          continue;
        }
      }
      if (cancellationRequests.contains(request.runId())) {
        return result(terminal(checkpoint, RuntimeStatus.CANCELLED, "cancelled by user"), null);
      }
      var outcome = executeTool(request, checkpoint, action);
      checkpoint = outcome.checkpoint();
      if (outcome.pausedOrTerminal()) {
        return result(checkpoint, null);
      }
      if (action.tool().equals("apply_patch") && confirmedEffect(action, request.workspace())) {
        return verify(request, checkpoint);
      }
    }
  }

  private ToolOutcome executeTool(
      AgentRunRequest request, AgentCheckpoint checkpoint, AgentAction action) {
    var digest = ActionDigests.create(action.tool(), action.input());
    var planDigest = ActionDigests.create("change_plan", mapper.valueToTree(checkpoint.plan()));
    var actionId = String.format("%06d-%s", checkpoint.steps() + 1, digest.substring(0, 12));
    var existing = journal.find(request.runId(), actionId).orElse(null);
    if (existing == null) {
      existing = existingByDigest(request.runId(), digest);
    }
    var descriptor = tools.descriptor(action.tool()).orElse(null);
    if (existing != null
        && existing.status() == ActionStatus.SUCCEEDED
        && action.tool().equals("read_file")) {
      action = nextReadPage(action, existing.resultSummary());
      digest = ActionDigests.create(action.tool(), action.input());
      actionId = String.format("%06d-%s", checkpoint.steps() + 1, digest.substring(0, 12));
      existing = existingByDigest(request.runId(), digest);
    }
    if (request.mode().readOnly()
        && (descriptor == null
            || descriptor.risk() != io.legacypilot.tool.spi.RiskLevel.READ_ONLY)) {
      var message = "READ_ONLY_MODE_VIOLATION: " + action.tool() + " is not permitted";
      event(
          checkpoint,
          "task-mode.denied",
          Map.of("mode", request.mode().name(), "tool", action.tool(), "digest", digest));
      remember(
          request.runId(),
          actionId + "-mode-denied",
          MemoryKind.FAILURE,
          message,
          Set.of("tool:" + action.tool()),
          true);
      var repeated =
          digest.equals(checkpoint.lastFailedDigest()) ? checkpoint.repeatedFailures() + 1 : 1;
      if (repeated >= MAXIMUM_IDENTICAL_FAILURES) {
        return new ToolOutcome(
            terminal(checkpoint, RuntimeStatus.FAILED, "repeated read-only mode violation"), true);
      }
      return new ToolOutcome(
          update(
              checkpoint,
              RuntimeStatus.EXECUTING,
              checkpoint.plan(),
              checkpoint.steps() + 1,
              checkpoint.retries() + 1,
              checkpoint.usage(),
              null,
              digest,
              repeated,
              message),
          false);
    }
    var approval =
        approvals
            .consumeMatching(request.runId(), digest, planDigest, clock.instant())
            .orElse(null);
    if (approval != null && approval.decision() == RuntimeApproval.Decision.DENIED) {
      var denied =
          terminal(checkpoint, RuntimeStatus.DENIED, "action denied by " + approval.actor());
      event(
          denied,
          "approval.denied",
          Map.of(
              "actor",
              approval.actor(),
              "digest",
              digest,
              "planDigest",
              planDigest,
              "reason",
              approval.reason()));
      return new ToolOutcome(denied, true);
    }
    if (approval != null) {
      event(
          checkpoint,
          "approval.consumed",
          Map.of(
              "actor", approval.actor(),
              "digest", digest,
              "planDigest", planDigest,
              "reason", approval.reason(),
              "scope", approval.scope().name()));
    }
    if (existing != null
        && existing.status() == ActionStatus.SUCCEEDED
        && !confirmedEffect(action, request.workspace())) {
      if (descriptor != null
          && descriptor.idempotency() == io.legacypilot.tool.spi.Idempotency.IDEMPOTENT) {
        existing = null;
      } else {
        existing =
            existing.transition(
                ActionStatus.NEEDS_REVIEW,
                existing.attempts(),
                "recorded success no longer matches workspace effect",
                clock.instant());
        journal.save(existing);
      }
    }
    if (existing != null && existing.status() == ActionStatus.SUCCEEDED) {
      event(
          checkpoint,
          "action.replay.skipped",
          Map.of("actionId", existing.actionId(), "digest", digest));
      remember(
          request.runId(),
          actionId + "-replay",
          MemoryKind.FACT,
          "Previously successful action was not executed again: " + existing.resultSummary(),
          Set.of("journal:" + actionId),
          true);
      return new ToolOutcome(
          update(
              checkpoint,
              RuntimeStatus.EXECUTING,
              checkpoint.plan(),
              checkpoint.steps() + 1,
              checkpoint.retries(),
              checkpoint.usage(),
              null,
              "",
              0,
              existing.resultSummary()),
          false);
    }
    if (existing != null
        && (existing.status() == ActionStatus.NEEDS_REVIEW
            || (existing.status() == ActionStatus.RUNNING
                && (descriptor == null
                    || descriptor.idempotency() != io.legacypilot.tool.spi.Idempotency.IDEMPOTENT)))
        && approval == null) {
      var review =
          existing.transition(
              ActionStatus.NEEDS_REVIEW,
              existing.attempts(),
              "previous execution outcome is uncertain",
              clock.instant());
      journal.save(review);
      remember(
          request.runId(),
          actionId + "-review",
          MemoryKind.PENDING_ACTION,
          "Action requires review before replay: " + action.tool(),
          Set.of("journal:" + actionId),
          true);
      return new ToolOutcome(
          update(
              checkpoint,
              RuntimeStatus.NEEDS_REVIEW,
              checkpoint.plan(),
              checkpoint.steps(),
              checkpoint.retries(),
              checkpoint.usage(),
              action,
              checkpoint.lastFailedDigest(),
              checkpoint.repeatedFailures(),
              "uncertain action requires explicit review: " + actionId),
          true);
    }
    var approved = approval == null ? Set.<String>of() : Set.of(digest);
    var context = new ToolContext(request.runId(), request.workspace(), approved, false);
    var canInvoke =
        descriptor != null
            && (descriptor.risk() == io.legacypilot.tool.spi.RiskLevel.READ_ONLY
                || approval != null);
    ActionRecord running = null;
    if (canInvoke) {
      var prepared =
          existing == null
              ? new ActionRecord(
                  actionId,
                  request.runId(),
                  action.tool(),
                  digest,
                  planDigest,
                  ActionStatus.PREPARED,
                  0,
                  "",
                  clock.instant())
              : existing;
      journal.save(prepared);
      running =
          prepared.transition(
              ActionStatus.RUNNING,
              prepared.attempts() + 1,
              "tool invocation started",
              clock.instant());
      journal.save(running);
      event(checkpoint, "action.started", Map.of("actionId", actionId, "digest", digest));
    }
    var result = tools.execute(action.tool(), context, action.input());
    if (running != null) {
      var summary =
          result.successful()
              ? Objects.toString(result.output(), "tool succeeded")
              : result.error().code() + ": " + result.error().message();
      journal.save(
          running.transition(
              result.successful() ? ActionStatus.SUCCEEDED : ActionStatus.FAILED,
              running.attempts(),
              summary,
              clock.instant()));
    }
    metrics.toolInvocation(action.tool(), result.status().name());
    event(
        checkpoint,
        "tool.completed",
        Map.of(
            "tool",
            action.tool(),
            "status",
            result.status().name(),
            "digest",
            digest,
            "policyRuleId",
            result.policyRuleId(),
            "policyRevision",
            result.policyRevision()));
    if (result.status() == ToolStatus.APPROVAL_REQUIRED) {
      remember(
          request.runId(),
          actionId + "-approval",
          MemoryKind.PENDING_ACTION,
          "Approval required for " + action.tool() + " with digest " + result.actionDigest(),
          Set.of("tool:" + action.tool()),
          true);
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
      remember(
          request.runId(),
          actionId + "-failure",
          MemoryKind.FAILURE,
          result.error().code() + ": " + result.error().message(),
          Set.of("tool:" + action.tool()),
          true);
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
    remember(
        request.runId(),
        actionId + "-success",
        MemoryKind.FACT,
        observation,
        Set.of("tool:" + action.tool(), "journal:" + actionId),
        true);
    var pendingAction =
        action.tool().equals("create_patch") ? preparedApplyAction(result.output()) : null;
    return new ToolOutcome(
        update(
            checkpoint,
            RuntimeStatus.EXECUTING,
            checkpoint.plan(),
            steps,
            checkpoint.retries(),
            checkpoint.usage(),
            pendingAction,
            "",
            0,
            observation),
        false);
  }

  private static AgentAction preparedApplyAction(com.fasterxml.jackson.databind.JsonNode patch) {
    var input = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    input.put("path", patch.path("path").asText());
    input.put("expectedSha256", patch.path("expectedSha256").asText());
    input.put("replacement", patch.path("replacement").asText());
    if (patch.has("startLine") && patch.has("endLine")) {
      input.put("startLine", patch.path("startLine").asInt());
      input.put("endLine", patch.path("endLine").asInt());
    }
    return new AgentAction(
        "TOOL",
        "apply_patch",
        input,
        "Apply the exact digest-bound patch prepared by create_patch");
  }

  private ActionRecord existingByDigest(String runId, String digest) {
    return journal.records(runId).reversed().stream()
        .filter(record -> record.actionDigest().equals(digest))
        .findFirst()
        .orElse(null);
  }

  private AgentAction nextReadPage(AgentAction action, String previousResult) {
    try {
      var previous = mapper.readTree(previousResult);
      var previousStart = previous.path("startLine").asInt(1);
      var previousEnd = previous.path("endLine").asInt(previousStart);
      var pageSize = Math.max(1, previousEnd - previousStart + 1);
      var nextStart = previous.path("nextStartLine").asInt(previousEnd + 1);
      return readPage(action, nextStart, pageSize);
    } catch (com.fasterxml.jackson.core.JsonProcessingException | ClassCastException exception) {
      var currentStart = action.input().path("startLine").asInt(1);
      return readPage(action, currentStart + 30, 30);
    }
  }

  private static AgentAction readPage(AgentAction action, int nextStart, int pageSize) {
    var input = (com.fasterxml.jackson.databind.node.ObjectNode) action.input().deepCopy();
    input.put("startLine", nextStart);
    if (input.has("endLine")) {
      input.put("endLine", nextStart + pageSize - 1);
    }
    return new AgentAction(action.type(), action.tool(), input, action.reason());
  }

  private static AgentAction normalizeNewFilePatch(AgentAction action) {
    if (!action.tool().equals("apply_patch")
        || !action
            .input()
            .path("expectedSha256")
            .asText()
            .equals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")) {
      return action;
    }
    var input = (com.fasterxml.jackson.databind.node.ObjectNode) action.input().deepCopy();
    input.remove(List.of("startLine", "endLine"));
    return new AgentAction(action.type(), action.tool(), input, action.reason());
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
    if (cancellationRequests.contains(request.runId())) {
      return result(terminal(checkpoint, RuntimeStatus.CANCELLED, "cancelled by user"), outcome);
    }
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

  private AgentRuntimeResult verifyReadOnly(
      AgentRunRequest request, AgentCheckpoint checkpoint, TaskArtifact artifact) {
    checkpoint =
        update(
            checkpoint,
            RuntimeStatus.VERIFYING,
            checkpoint.plan(),
            checkpoint.steps() + 1,
            checkpoint.retries(),
            checkpoint.usage(),
            null,
            checkpoint.lastFailedDigest(),
            checkpoint.repeatedFailures(),
            "read-only artifact verification started");
    var verified = readOnlyVerifier.verify(request, artifact, tools);
    var outcome = verified.outcome();
    if (cancellationRequests.contains(request.runId())) {
      return result(
          terminal(checkpoint, RuntimeStatus.CANCELLED, "cancelled by user"),
          outcome,
          artifact,
          verified.metrics());
    }
    event(
        checkpoint,
        "artifact.verification.completed",
        Map.of(
            "mode",
            request.mode().name(),
            "success",
            Boolean.toString(outcome.successful()),
            "evidenceCount",
            verified.metrics().getOrDefault("evidenceCount", "0")));
    if (outcome.successful()) {
      checkpoint = terminal(checkpoint, RuntimeStatus.SUCCEEDED, "source-grounded result verified");
      return result(checkpoint, outcome, artifact, verified.metrics());
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
    checkpoint = terminal(checkpoint, RuntimeStatus.FAILED, "read-only result verification failed");
    return result(checkpoint, outcome, artifact, verified.metrics());
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
    if (status == RuntimeStatus.CANCELLED) {
      cancellationRequests.remove(checkpoint.runId());
    }
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
    ensureLease(old.runId());
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
    trace.record(checkpoint.runId(), type, clock.instant(), attributes);
  }

  private void remember(
      String runId,
      String id,
      MemoryKind kind,
      String content,
      Set<String> sources,
      boolean verified) {
    memories.append(
        new TaskMemory(
            id,
            runId,
            kind,
            content.isBlank() ? "empty observation" : content,
            sources,
            verified,
            clock.instant(),
            clock.instant().plus(Duration.ofDays(7))));
  }

  private void ensureLease(String runId) {
    var failure = new java.util.concurrent.atomic.AtomicReference<LeaseLostException>();
    activeLeases.computeIfPresent(
        runId,
        (ignored, current) ->
            leases
                .renew(current, clock.instant(), leaseTtl)
                .orElseGet(
                    () -> {
                      failure.set(new LeaseLostException("run lease was lost for " + runId));
                      return current;
                    }));
    if (failure.get() != null) {
      throw failure.get();
    }
  }

  private void heartbeat(String runId) {
    try {
      ensureLease(runId);
    } catch (RuntimeException ignored) {
      // The foreground execution observes and reports the lost lease at its next safe checkpoint.
    }
  }

  private AgentRuntimeResult leaseConflict(String runId) {
    var previous =
        checkpoints
            .load(runId)
            .orElse(
                new AgentCheckpoint(
                    runId,
                    RuntimeStatus.PLANNING,
                    null,
                    0,
                    0,
                    ModelUsage.NONE,
                    clock.instant(),
                    clock.instant(),
                    null,
                    "",
                    0,
                    ""));
    var checkpoint =
        new AgentCheckpoint(
            runId,
            RuntimeStatus.LEASE_CONFLICT,
            previous.plan(),
            previous.steps(),
            previous.retries(),
            previous.usage(),
            previous.startedAt(),
            clock.instant(),
            previous.pendingAction(),
            previous.lastFailedDigest(),
            previous.repeatedFailures(),
            "run is owned by another process");
    var report = report(checkpoint, null, null, Map.of());
    return new AgentRuntimeResult(checkpoint, null, report);
  }

  private AgentRuntimeResult cancelled(String runId) {
    var checkpoint =
        checkpoints
            .load(runId)
            .orElse(
                new AgentCheckpoint(
                    runId,
                    RuntimeStatus.PLANNING,
                    null,
                    0,
                    0,
                    ModelUsage.NONE,
                    clock.instant(),
                    clock.instant(),
                    null,
                    "",
                    0,
                    ""));
    cancellationRequests.remove(runId);
    return result(terminal(checkpoint, RuntimeStatus.CANCELLED, "cancelled by user"), null);
  }

  private RunReport report(
      AgentCheckpoint checkpoint,
      VerificationOutcome verificationOutcome,
      TaskArtifact artifact,
      Map<String, String> groundingMetrics) {
    var evidence = new ArrayList<Map<String, String>>();
    var risk = "UNKNOWN";
    if (verificationOutcome != null) {
      risk = verificationOutcome.risk().name();
      verificationOutcome
          .evidence()
          .forEach(
              item -> {
                var details = new java.util.LinkedHashMap<String, String>();
                details.put("name", item.name());
                details.put("status", item.status().name());
                details.put("required", Boolean.toString(item.required()));
                details.put("repairable", Boolean.toString(item.repairable()));
                details.put("evidence", item.summary());
                details.put("command", item.command());
                details.put("exitCode", item.exitCode() == null ? "" : item.exitCode().toString());
                details.put("artifactReference", item.artifactReference());
                details.put("duration", item.duration().toString());
                evidence.add(Map.copyOf(details));
              });
    }
    var mode =
        requests.load(checkpoint.runId()).map(AgentRunRequest::mode).orElse(TaskMode.CHANGE).name();
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
        trace.events(checkpoint.runId()),
        mode,
        artifact,
        groundingMetrics);
  }

  private AgentRuntimeResult result(
      AgentCheckpoint checkpoint, VerificationOutcome verificationOutcome) {
    return result(checkpoint, verificationOutcome, null, Map.of());
  }

  private AgentRuntimeResult result(
      AgentCheckpoint checkpoint,
      VerificationOutcome verificationOutcome,
      TaskArtifact artifact,
      Map<String, String> groundingMetrics) {
    var report = report(checkpoint, verificationOutcome, artifact, groundingMetrics);
    reports.save(report);
    return new AgentRuntimeResult(checkpoint, verificationOutcome, report);
  }

  private static boolean confirmedEffect(AgentAction action, java.nio.file.Path workspace) {
    if (action.tool().equals("create_patch") || action.tool().equals("read_file")) {
      return false;
    }
    if (!action.tool().equals("apply_patch")) {
      return true;
    }
    try {
      var relative = java.nio.file.Path.of(action.input().path("path").asText()).normalize();
      var target = workspace.toAbsolutePath().normalize().resolve(relative).normalize();
      return !relative.isAbsolute()
          && !relative.startsWith("..")
          && target.startsWith(workspace.toAbsolutePath().normalize())
          && java.nio.file.Files.isRegularFile(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)
          && confirmedPatchContent(java.nio.file.Files.readString(target), action.input());
    } catch (RuntimeException | java.io.IOException exception) {
      return false;
    }
  }

  private static boolean confirmedPatchContent(
      String actual, com.fasterxml.jackson.databind.JsonNode input) {
    if (!input.has("startLine") || !input.has("endLine")) {
      return actual.equals(input.path("replacement").asText());
    }
    // A range patch is already confirmed by the successful, digest-bound journal record. The
    // replacement fragment must still be present so a restart cannot mistake an untouched file for
    // a completed effect.
    return actual.contains(input.path("replacement").asText());
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

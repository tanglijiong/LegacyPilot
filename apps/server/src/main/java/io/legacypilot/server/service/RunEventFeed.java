package io.legacypilot.server.service;

import io.legacypilot.observability.TraceEvent;
import io.legacypilot.observability.TraceSink;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Converts the durable, redacted runtime trace into the stable public run-event contract. */
@Service
public final class RunEventFeed {
  private final TraceSink trace;

  public RunEventFeed(TraceSink trace) {
    this.trace = trace;
  }

  public List<RunEvent> after(String runId, int lastSequence) {
    var result = new ArrayList<RunEvent>();
    var correlationId = "";
    for (var event : trace.events(runId)) {
      correlationId = event.attributes().getOrDefault("correlationId", correlationId);
      if (event.sequence() > lastSequence) {
        result.add(toRunEvent(event, correlationId));
      }
    }
    return List.copyOf(result);
  }

  private static RunEvent toRunEvent(TraceEvent event, String correlationId) {
    return new RunEvent(
        event.sequence(),
        event.occurredAt(),
        phase(event),
        status(event),
        summary(event),
        correlationId,
        event.type());
  }

  private static String phase(TraceEvent event) {
    if (event.type().startsWith("command.")) {
      return "QUEUED";
    }
    if (event.type().startsWith("verification.")
        || event.type().startsWith("artifact.verification.")) {
      return "VERIFYING";
    }
    if (event.type().equals("run.completed") || event.type().equals("run.cancelled")) {
      return "COMPLETED";
    }
    if (event.type().equals("run.started")
        || event.type().equals("plan.created")
        || event.type().equals("context.compacted")
        || event.type().equals("model.failed")) {
      return "PLANNING";
    }
    return "EXECUTING";
  }

  private static String status(TraceEvent event) {
    if (event.type().equals("run.completed")) {
      return event.attributes().getOrDefault("status", "COMPLETED");
    }
    if (event.type().equals("tool.completed")
        && "APPROVAL_REQUIRED".equals(event.attributes().get("status"))) {
      return "WAITING_FOR_APPROVAL";
    }
    return phase(event);
  }

  private static String summary(TraceEvent event) {
    Map<String, String> attributes = event.attributes();
    return switch (event.type()) {
      case "command.accepted" ->
          attributes.getOrDefault("operation", "EXECUTE") + " command accepted";
      case "attempt.retried" -> "Retry resumed from the durable checkpoint";
      case "attempt.replanned" -> "A new plan was requested for this attempt";
      case "run.cancelled" -> "Run cancelled by the user";
      case "lease.acquired" -> "Background worker acquired the run";
      case "lease.released" -> "Background worker released the run";
      case "run.started" -> "Agent run started";
      case "plan.created" ->
          "Execution plan created (risk " + attributes.getOrDefault("risk", "UNKNOWN") + ")";
      case "context.compacted" -> "Relevant task context prepared";
      case "action.started" -> "Controlled action started";
      case "action.replay.skipped" -> "Previously completed action was not repeated";
      case "tool.completed" ->
          "Tool "
              + attributes.getOrDefault("tool", "action")
              + " finished with "
              + attributes.getOrDefault("status", "UNKNOWN");
      case "approval.consumed" -> "Independent approval consumed";
      case "approval.denied" -> "Independent approval denied";
      case "verification.completed", "artifact.verification.completed" ->
          "Verification finished: "
              + (Boolean.parseBoolean(attributes.getOrDefault("success", "false"))
                  ? "passed"
                  : "failed");
      case "model.failed" ->
          "Model request failed (" + attributes.getOrDefault("type", "UNKNOWN") + ")";
      case "task-mode.denied" -> "Read-only task blocked a write-capable action";
      case "run.completed" -> "Run completed with " + attributes.getOrDefault("status", "UNKNOWN");
      default -> event.type().replace('.', ' ').toLowerCase(Locale.ROOT);
    };
  }

  public record RunEvent(
      int sequence,
      Instant time,
      String phase,
      String status,
      String summary,
      String correlationId,
      String type) {}
}

package io.legacypilot.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.application.port.WorkspaceService;
import io.legacypilot.application.service.GetProjectUseCase;
import io.legacypilot.application.service.GetRunStatusUseCase;
import io.legacypilot.application.service.GetTaskUseCase;
import io.legacypilot.domain.task.TaskMode;
import io.legacypilot.observability.ReportStore;
import io.legacypilot.observability.TraceEvent;
import io.legacypilot.observability.TraceSink;
import io.legacypilot.runtime.AgentAction;
import io.legacypilot.runtime.AgentCheckpoint;
import io.legacypilot.runtime.CheckpointStore;
import io.legacypilot.runtime.RuntimeStatus;
import io.legacypilot.tool.spi.ActionDigests;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/** Builds the server-authoritative decision packet used by the CHANGE review workbench. */
@Service
public final class ChangeReviewService {
  private static final int MAX_DIFF_LINES = 800;
  private static final int MAX_FILE_BYTES = 1024 * 1024;

  private final GetRunStatusUseCase getRun;
  private final GetTaskUseCase getTask;
  private final GetProjectUseCase getProject;
  private final WorkspaceService workspaces;
  private final CheckpointStore checkpoints;
  private final ReportStore reports;
  private final TraceSink trace;
  private final ObjectMapper mapper;

  public ChangeReviewService(
      GetRunStatusUseCase getRun,
      GetTaskUseCase getTask,
      GetProjectUseCase getProject,
      WorkspaceService workspaces,
      CheckpointStore checkpoints,
      ReportStore reports,
      TraceSink trace,
      ObjectMapper mapper) {
    this.getRun = Objects.requireNonNull(getRun);
    this.getTask = Objects.requireNonNull(getTask);
    this.getProject = Objects.requireNonNull(getProject);
    this.workspaces = Objects.requireNonNull(workspaces);
    this.checkpoints = Objects.requireNonNull(checkpoints);
    this.reports = Objects.requireNonNull(reports);
    this.trace = Objects.requireNonNull(trace);
    this.mapper = Objects.requireNonNull(mapper);
  }

  public ChangeReview review(String runId) {
    var run = getRun.get(runId);
    var task = getTask.get(run.taskId().value());
    if (task.mode() != TaskMode.CHANGE) {
      throw new IllegalStateException("change review is only available for CHANGE tasks");
    }
    var checkpoint =
        checkpoints
            .load(runId)
            .orElseThrow(() -> new IllegalStateException("run has no execution checkpoint"));
    var plan = checkpoint.plan();
    var action = action(checkpoint);
    var paths = new LinkedHashSet<String>();
    if (plan != null) {
      paths.addAll(plan.affectedFiles());
    }
    if (checkpoint.pendingAction() != null
        && checkpoint.pendingAction().input().path("path").isTextual()) {
      paths.add(checkpoint.pendingAction().input().path("path").asText());
    }
    var project = getProject.get(task.projectId().value());
    var descriptor = workspaces.open(project, run.id(), run.workspaceId());
    var root = Path.of(descriptor.path()).toAbsolutePath().normalize();
    var diffs = paths.stream().map(path -> diff(root, path, checkpoint.pendingAction())).toList();
    var report = reports.load(runId).orElse(null);
    var verification =
        report == null
            ? List.<VerificationView>of()
            : report.verification().stream().map(ChangeReviewService::verification).toList();
    return new ChangeReview(
        runId,
        run.status().name(),
        plan == null ? 0 : plan.version(),
        plan == null ? List.of() : plan.steps(),
        List.copyOf(paths),
        plan == null ? "UNKNOWN" : plan.risk(),
        plan == null ? "Plan is not available yet" : plan.rationale(),
        action,
        policy(checkpoint),
        diffs,
        verification,
        decision(runId),
        run.status().isTerminal());
  }

  public ActionView requireCurrentApproval(String runId, String actionDigest, String planDigest) {
    var review = review(runId);
    if (!"WAITING_FOR_APPROVAL".equals(review.status()) || review.action() == null) {
      throw new IllegalStateException("run is not waiting for approval");
    }
    if (!review.action().actionDigest().equals(actionDigest)
        || !review.action().planDigest().equals(planDigest)) {
      throw new IllegalStateException(
          "approval digest is stale or does not match the pending action");
    }
    return review.action();
  }

  private ActionView action(AgentCheckpoint checkpoint) {
    var pending = checkpoint.pendingAction();
    if (pending == null || checkpoint.plan() == null) {
      return null;
    }
    var actionDigest = ActionDigests.create(pending.tool(), pending.input());
    var planDigest = ActionDigests.create("change_plan", mapper.valueToTree(checkpoint.plan()));
    var path = pending.input().path("path").asText("");
    var summary =
        path.isBlank()
            ? "Execute " + pending.tool()
            : "Modify " + path + " using " + pending.tool();
    return new ActionView(actionDigest, planDigest, pending.tool(), pending.reason(), summary);
  }

  private static List<PolicyCheck> policy(AgentCheckpoint checkpoint) {
    var waiting = checkpoint.status() == RuntimeStatus.WAITING_FOR_APPROVAL;
    return List.of(
        new PolicyCheck(
            "workspace-boundary", "PASSED", "Only the managed run workspace can be modified"),
        new PolicyCheck(
            "separation-of-duties",
            waiting ? "REQUIRED" : "PASSED",
            "The run initiator cannot approve this action"),
        new PolicyCheck(
            "digest-binding",
            waiting ? "REQUIRED" : "PASSED",
            "Approval is bound to the exact action and plan digests"));
  }

  private FileDiff diff(Path root, String relative, AgentAction pending) {
    try {
      var path = safePath(root, relative);
      var before = gitHead(root, relative);
      var after = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ? readBounded(path) : "";
      var preview =
          pending != null && relative.equals(pending.input().path("path").asText())
              ? pendingPreview(after, pending.input())
              : after;
      return compare(relative, before, preview);
    } catch (RuntimeException exception) {
      return new FileDiff(relative, "UNAVAILABLE", 0, 0, true, List.of(), exception.getMessage());
    }
  }

  private static String pendingPreview(
      String current, com.fasterxml.jackson.databind.JsonNode input) {
    var replacement = input.path("replacement").asText(current);
    if (!input.has("startLine") || !input.has("endLine")) {
      return replacement;
    }
    return replaceLines(
        current, input.path("startLine").asInt(), input.path("endLine").asInt(), replacement);
  }

  private static String replaceLines(
      String current, int startLine, int endLine, String replacement) {
    if (startLine < 1 || endLine < startLine) {
      throw new IllegalArgumentException("invalid pending patch line range");
    }
    var lines = current.split("\\n", -1);
    var logicalLineCount = current.endsWith("\n") ? lines.length - 1 : lines.length;
    if (endLine > logicalLineCount) {
      throw new IllegalArgumentException("pending patch line range exceeds the file");
    }
    var before = String.join("\n", java.util.Arrays.copyOfRange(lines, 0, startLine - 1));
    var after = String.join("\n", java.util.Arrays.copyOfRange(lines, endLine, lines.length));
    var result = new StringBuilder(current.length() + replacement.length());
    if (!before.isEmpty()) {
      result.append(before).append('\n');
    }
    result.append(replacement);
    if (!replacement.endsWith("\n") && !after.isEmpty()) {
      result.append('\n');
    }
    return result.append(after).toString();
  }

  private static Path safePath(Path root, String relative) {
    if (relative == null
        || relative.isBlank()
        || relative.contains("\\")
        || Path.of(relative).isAbsolute()) {
      throw new IllegalArgumentException("review path is invalid");
    }
    var path = root.resolve(relative).normalize();
    if (!path.startsWith(root) || Path.of(relative).normalize().startsWith("..")) {
      throw new IllegalArgumentException("review path escapes workspace");
    }
    for (var cursor = path; cursor != null && !cursor.equals(root); cursor = cursor.getParent()) {
      if (Files.isSymbolicLink(cursor)) {
        throw new IllegalArgumentException("review path contains a symbolic link");
      }
    }
    return path;
  }

  private static String readBounded(Path path) {
    try {
      if (Files.size(path) > MAX_FILE_BYTES) {
        throw new IllegalArgumentException("review file is too large");
      }
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("unable to read review file", exception);
    }
  }

  private static String gitHead(Path root, String relative) {
    try {
      var process =
          new ProcessBuilder("git", "show", "HEAD:" + relative)
              .directory(root.toFile())
              .redirectErrorStream(true)
              .start();
      var bytes = process.getInputStream().readNBytes(MAX_FILE_BYTES + 1);
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("git show timed out");
      }
      if (process.exitValue() != 0) {
        return "";
      }
      if (bytes.length > MAX_FILE_BYTES) {
        throw new IllegalArgumentException("review file is too large");
      }
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("unable to read baseline file", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("baseline read interrupted", exception);
    }
  }

  private static FileDiff compare(String path, String before, String after) {
    var oldLines = before.lines().toList();
    var newLines = after.lines().toList();
    int prefix = 0;
    while (prefix < oldLines.size()
        && prefix < newLines.size()
        && oldLines.get(prefix).equals(newLines.get(prefix))) {
      prefix++;
    }
    int suffix = 0;
    while (suffix < oldLines.size() - prefix
        && suffix < newLines.size() - prefix
        && oldLines
            .get(oldLines.size() - suffix - 1)
            .equals(newLines.get(newLines.size() - suffix - 1))) {
      suffix++;
    }
    var lines = new ArrayList<DiffLine>();
    int start = Math.max(0, prefix - 3);
    int oldEnd = oldLines.size() - suffix;
    int newEnd = newLines.size() - suffix;
    for (int index = start; index < prefix; index++) {
      lines.add(new DiffLine(index + 1, index + 1, "CONTEXT", oldLines.get(index)));
    }
    int deletions = 0;
    for (int index = prefix; index < oldEnd; index++) {
      deletions++;
      if (lines.size() < MAX_DIFF_LINES) {
        lines.add(new DiffLine(index + 1, null, "DELETE", oldLines.get(index)));
      }
    }
    int additions = 0;
    for (int index = prefix; index < newEnd; index++) {
      additions++;
      if (lines.size() < MAX_DIFF_LINES) {
        lines.add(new DiffLine(null, index + 1, "ADD", newLines.get(index)));
      }
    }
    int contextEnd = Math.min(oldLines.size(), oldEnd + 3);
    for (int index = oldEnd; index < contextEnd && lines.size() < MAX_DIFF_LINES; index++) {
      var newIndex = newEnd + index - oldEnd;
      lines.add(new DiffLine(index + 1, newIndex + 1, "CONTEXT", oldLines.get(index)));
    }
    var status =
        additions == 0 && deletions == 0
            ? "UNCHANGED"
            : before.isEmpty() ? "ADDED" : after.isEmpty() ? "DELETED" : "MODIFIED";
    return new FileDiff(
        path, status, additions, deletions, additions + deletions > MAX_DIFF_LINES, lines, "");
  }

  private static VerificationView verification(Map<String, String> item) {
    var name = item.getOrDefault("name", "verification");
    var lower = name.toLowerCase(Locale.ROOT);
    var category =
        lower.contains("compile")
            ? "COMPILE"
            : lower.contains("static") || lower.contains("integrity") || lower.contains("diff")
                ? "STATIC_ANALYSIS"
                : lower.contains("coverage") ? "COVERAGE" : "TEST";
    return new VerificationView(
        category,
        name,
        item.getOrDefault("status", "UNKNOWN"),
        item.getOrDefault("required", "true"),
        item.getOrDefault("evidence", ""),
        item.getOrDefault("command", ""),
        item.getOrDefault("duration", ""));
  }

  private DecisionView decision(String runId) {
    return trace.events(runId).reversed().stream()
        .filter(
            event ->
                event.type().equals("approval.consumed") || event.type().equals("approval.denied"))
        .findFirst()
        .map(ChangeReviewService::decision)
        .orElse(null);
  }

  private static DecisionView decision(TraceEvent event) {
    var attributes = event.attributes();
    return new DecisionView(
        event.type().equals("approval.denied") ? "DENIED" : "APPROVED",
        attributes.getOrDefault("actor", "unknown"),
        attributes.getOrDefault("reason", ""),
        attributes.getOrDefault("digest", ""),
        attributes.getOrDefault("planDigest", ""),
        event.occurredAt());
  }

  public record ChangeReview(
      String runId,
      String status,
      int planVersion,
      List<String> steps,
      List<String> affectedFiles,
      String risk,
      String rationale,
      ActionView action,
      List<PolicyCheck> policyChecks,
      List<FileDiff> diff,
      List<VerificationView> verification,
      DecisionView decision,
      boolean replanAvailable) {
    public ChangeReview {
      steps = List.copyOf(steps);
      affectedFiles = List.copyOf(affectedFiles);
      policyChecks = List.copyOf(policyChecks);
      diff = List.copyOf(diff);
      verification = List.copyOf(verification);
    }

    @Override
    public List<String> steps() {
      return List.copyOf(steps);
    }

    @Override
    public List<String> affectedFiles() {
      return List.copyOf(affectedFiles);
    }

    @Override
    public List<PolicyCheck> policyChecks() {
      return List.copyOf(policyChecks);
    }

    @Override
    public List<FileDiff> diff() {
      return List.copyOf(diff);
    }

    @Override
    public List<VerificationView> verification() {
      return List.copyOf(verification);
    }
  }

  public record ActionView(
      String actionDigest, String planDigest, String tool, String reason, String summary) {}

  public record PolicyCheck(String name, String status, String detail) {}

  public record FileDiff(
      String path,
      String status,
      int additions,
      int deletions,
      boolean truncated,
      List<DiffLine> lines,
      String error) {
    public FileDiff {
      lines = List.copyOf(lines);
    }

    @Override
    public List<DiffLine> lines() {
      return List.copyOf(lines);
    }
  }

  public record DiffLine(Integer oldLine, Integer newLine, String kind, String text) {}

  public record VerificationView(
      String category,
      String name,
      String status,
      String required,
      String summary,
      String command,
      String duration) {}

  public record DecisionView(
      String decision,
      String actor,
      String reason,
      String actionDigest,
      String planDigest,
      Instant decidedAt) {}
}

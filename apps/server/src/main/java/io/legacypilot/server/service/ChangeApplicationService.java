package io.legacypilot.server.service;

import io.legacypilot.application.port.WorkspaceService;
import io.legacypilot.application.service.GetProjectUseCase;
import io.legacypilot.application.service.GetRunStatusUseCase;
import io.legacypilot.application.service.GetTaskUseCase;
import io.legacypilot.domain.run.RunStatus;
import io.legacypilot.domain.task.TaskMode;
import io.legacypilot.observability.TraceSink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Safely applies a verified CHANGE workspace back to an explicitly selected local project. */
@Service
public final class ChangeApplicationService {
  private final GetRunStatusUseCase getRun;
  private final GetTaskUseCase getTask;
  private final GetProjectUseCase getProject;
  private final WorkspaceService workspaces;
  private final ChangeReviewService reviews;
  private final TraceSink trace;
  private final Clock clock;

  public ChangeApplicationService(
      GetRunStatusUseCase getRun,
      GetTaskUseCase getTask,
      GetProjectUseCase getProject,
      WorkspaceService workspaces,
      ChangeReviewService reviews,
      TraceSink trace,
      Clock clock) {
    this.getRun = Objects.requireNonNull(getRun);
    this.getTask = Objects.requireNonNull(getTask);
    this.getProject = Objects.requireNonNull(getProject);
    this.workspaces = Objects.requireNonNull(workspaces);
    this.reviews = Objects.requireNonNull(reviews);
    this.trace = Objects.requireNonNull(trace);
    this.clock = Objects.requireNonNull(clock);
  }

  public synchronized ApplicationResult apply(String runId, String requestedTarget, String actor) {
    var run = getRun.get(runId);
    var task = getTask.get(run.taskId().value());
    if (task.mode() != TaskMode.CHANGE || run.status() != RunStatus.SUCCEEDED) {
      throw new IllegalStateException("only a successful CHANGE run can be applied");
    }
    var project = getProject.get(task.projectId().value());
    var target = resolveTarget(project.source().value(), requestedTarget);
    var workspace =
        Path.of(workspaces.open(project, run.id(), run.workspaceId()).path())
            .toAbsolutePath()
            .normalize();
    var review = reviews.review(runId);
    if (review.diff().isEmpty()) {
      throw new IllegalStateException("the run has no file changes to apply");
    }

    var operations = new ArrayList<Operation>();
    for (var diff : review.diff()) {
      var relative = safeRelative(diff.path());
      var destination = safeDestination(target, relative);
      var baseline = gitShow(project.repositoryPath(), project.baseRevision().value(), relative);
      var desired =
          Files.exists(workspace.resolve(relative))
              ? readRegularFile(workspace.resolve(relative), "workspace change")
              : Optional.<byte[]>empty();
      var current =
          Files.exists(destination)
              ? readRegularFile(destination, "target file")
              : Optional.<byte[]>empty();
      if (same(current, desired)) {
        operations.add(new Operation(relative, destination, desired, current, true));
        continue;
      }
      if (!same(current, baseline)) {
        throw new IllegalStateException("目标文件已在任务开始后发生变化，拒绝覆盖: " + relative);
      }
      operations.add(new Operation(relative, destination, desired, current, false));
    }

    var applied = new ArrayList<String>();
    var backups = new ArrayList<Backup>();
    var completed = false;
    try {
      for (var operation : operations) {
        if (operation.alreadyApplied()) {
          continue;
        }
        backups.add(new Backup(operation.destination(), operation.current()));
        write(operation.destination(), operation.desired());
        applied.add(operation.relative().toString().replace('\\', '/'));
      }
      completed = true;
    } finally {
      if (!completed) {
        rollback(backups);
      }
    }
    trace.record(
        runId,
        "change.applied-to-source",
        clock.instant(),
        Map.of(
            "actor", actor,
            "target", target.toString(),
            "files", Integer.toString(applied.size())));
    return new ApplicationResult(
        runId,
        target.toString(),
        List.copyOf(applied),
        operations.size() - applied.size(),
        applied.isEmpty() ? "ALREADY_APPLIED" : "APPLIED");
  }

  private static Path resolveTarget(String source, String requested) {
    var value = requested == null ? "" : requested.trim();
    if (value.isEmpty() && !source.startsWith("directory://") && !source.startsWith("http")) {
      value = source;
    }
    if (value.isEmpty()) {
      throw new IllegalArgumentException("浏览器上传的项目需要填写 IDEA 当前打开的本地项目绝对路径");
    }
    try {
      var target = Path.of(value);
      if (!target.isAbsolute()) {
        throw new IllegalArgumentException("目标项目路径必须是绝对路径");
      }
      target = target.toRealPath(LinkOption.NOFOLLOW_LINKS);
      if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
        throw new IllegalArgumentException("目标项目路径必须是非符号链接目录");
      }
      return target;
    } catch (IOException exception) {
      throw new IllegalArgumentException("目标项目目录不存在或不可访问", exception);
    }
  }

  private static Path safeRelative(String value) {
    var relative = Path.of(value.replace('\\', '/')).normalize();
    if (relative.isAbsolute() || relative.getNameCount() == 0 || relative.startsWith("..")) {
      throw new IllegalStateException("change path escapes the project: " + value);
    }
    return relative;
  }

  private static Path safeDestination(Path root, Path relative) {
    var destination = root.resolve(relative).normalize();
    if (!destination.startsWith(root)) {
      throw new IllegalStateException("change path escapes the target project");
    }
    var cursor = destination.getParent();
    while (cursor != null && cursor.startsWith(root)) {
      if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cursor)) {
        throw new IllegalStateException("目标路径包含符号链接，拒绝写入: " + relative);
      }
      if (cursor.equals(root)) {
        break;
      }
      cursor = cursor.getParent();
    }
    if (Files.isSymbolicLink(destination)) {
      throw new IllegalStateException("目标文件是符号链接，拒绝写入: " + relative);
    }
    return destination;
  }

  private static Optional<byte[]> gitShow(String repository, String revision, Path relative) {
    try {
      var process =
          new ProcessBuilder(
                  "git",
                  "-C",
                  repository,
                  "show",
                  revision + ":" + relative.toString().replace('\\', '/'))
              .redirectErrorStream(false)
              .start();
      var output = process.getInputStream().readAllBytes();
      var error = process.getErrorStream().readAllBytes();
      var exit = process.waitFor();
      if (exit == 0) {
        return Optional.of(output);
      }
      var message = new String(error, java.nio.charset.StandardCharsets.UTF_8);
      if (message.contains("does not exist in") || message.contains("exists on disk, but not in")) {
        return Optional.empty();
      }
      throw new IllegalStateException("无法读取项目基线: " + message.trim());
    } catch (IOException exception) {
      throw new IllegalStateException("无法启动 Git 基线检查", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Git 基线检查被中断", exception);
    }
  }

  private static Optional<byte[]> readRegularFile(Path path, String label) {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
      throw new IllegalStateException(label + " is not a regular file: " + path);
    }
    try {
      return Optional.of(Files.readAllBytes(path));
    } catch (IOException exception) {
      throw new IllegalStateException("无法读取文件: " + path, exception);
    }
  }

  private static boolean same(Optional<byte[]> left, Optional<byte[]> right) {
    return left.isEmpty()
        ? right.isEmpty()
        : right.isPresent() && MessageDigest.isEqual(left.get(), right.get());
  }

  private static void write(Path destination, Optional<byte[]> content) {
    try {
      if (content.isEmpty()) {
        Files.deleteIfExists(destination);
        return;
      }
      var parent = Objects.requireNonNull(destination.getParent(), "destination requires a parent");
      Files.createDirectories(parent);
      var temporary = Files.createTempFile(parent, ".legacypilot-", ".tmp");
      try {
        Files.write(temporary, content.orElseThrow(), StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(
            temporary,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("无法写入目标文件: " + destination, exception);
    }
  }

  private static void rollback(List<Backup> backups) {
    for (var index = backups.size() - 1; index >= 0; index--) {
      var backup = backups.get(index);
      try {
        write(backup.destination(), backup.content());
      } catch (RuntimeException ignored) {
        // Preserve the original apply failure. The trace and target state remain available for
        // review.
      }
    }
  }

  private record Operation(
      Path relative,
      Path destination,
      Optional<byte[]> desired,
      Optional<byte[]> current,
      boolean alreadyApplied) {}

  private record Backup(Path destination, Optional<byte[]> content) {}

  public record ApplicationResult(
      String runId,
      String targetPath,
      List<String> appliedFiles,
      int alreadyAppliedFiles,
      String status) {
    public ApplicationResult {
      appliedFiles = List.copyOf(appliedFiles);
    }
  }
}

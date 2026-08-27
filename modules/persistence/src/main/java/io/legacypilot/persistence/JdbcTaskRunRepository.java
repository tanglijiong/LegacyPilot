package io.legacypilot.persistence;

import io.legacypilot.application.error.ConcurrentUpdateException;
import io.legacypilot.application.port.TaskRunRepository;
import io.legacypilot.domain.run.RunId;
import io.legacypilot.domain.run.RunStatus;
import io.legacypilot.domain.run.StatusTransition;
import io.legacypilot.domain.run.TaskRun;
import io.legacypilot.domain.run.WorkspaceId;
import io.legacypilot.domain.task.TaskId;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

public class JdbcTaskRunRepository implements TaskRunRepository {

  private final JdbcClient jdbc;

  public JdbcTaskRunRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public TaskRun add(TaskRun run) {
    jdbc.sql(
            """
            INSERT INTO task_runs
              (id, task_id, status, version, workspace_id, recovery_target,
               terminal_reason, created_at, updated_at)
            VALUES
              (:id, :taskId, :status, :version, :workspaceId, :recoveryTarget,
               :terminalReason, :createdAt, :updatedAt)
            """)
        .param("id", run.id().value())
        .param("taskId", run.taskId().value())
        .param("status", run.status().name())
        .param("version", run.version())
        .param("workspaceId", value(run.workspaceId()))
        .param("recoveryTarget", name(run.recoveryTarget()))
        .param("terminalReason", run.terminalReason())
        .param("createdAt", Timestamp.from(run.createdAt()))
        .param("updatedAt", Timestamp.from(run.updatedAt()))
        .update();
    replaceHistory(run);
    return run;
  }

  @Override
  @Transactional
  public TaskRun update(TaskRun run) {
    int updated =
        jdbc.sql(
                """
                UPDATE task_runs
                SET status = :status,
                    version = :newVersion,
                    workspace_id = :workspaceId,
                    recovery_target = :recoveryTarget,
                    terminal_reason = :terminalReason,
                    updated_at = :updatedAt
                WHERE id = :id AND version = :expectedVersion
                """)
            .param("status", run.status().name())
            .param("newVersion", run.version() + 1)
            .param("workspaceId", value(run.workspaceId()))
            .param("recoveryTarget", name(run.recoveryTarget()))
            .param("terminalReason", run.terminalReason())
            .param("updatedAt", Timestamp.from(run.updatedAt()))
            .param("id", run.id().value())
            .param("expectedVersion", run.version())
            .update();
    if (updated != 1) {
      throw new ConcurrentUpdateException("TaskRun", run.id().value());
    }
    replaceHistory(run);
    return run.withVersion(run.version() + 1);
  }

  @Override
  public Optional<TaskRun> findById(RunId id) {
    return jdbc.sql(
            """
            SELECT id, task_id, status, version, workspace_id, recovery_target,
                   terminal_reason, created_at, updated_at
            FROM task_runs
            WHERE id = :id
            """)
        .param("id", id.value())
        .query(
            (resultSet, rowNumber) ->
                TaskRun.restore(
                    new RunId(resultSet.getString("id")),
                    new TaskId(resultSet.getString("task_id")),
                    RunStatus.valueOf(resultSet.getString("status")),
                    resultSet.getLong("version"),
                    workspaceId(resultSet.getString("workspace_id")),
                    status(resultSet.getString("recovery_target")),
                    resultSet.getString("terminal_reason"),
                    resultSet.getTimestamp("created_at").toInstant(),
                    resultSet.getTimestamp("updated_at").toInstant(),
                    findHistory(id)))
        .optional();
  }

  private void replaceHistory(TaskRun run) {
    jdbc.sql("DELETE FROM run_transitions WHERE run_id = :runId")
        .param("runId", run.id().value())
        .update();
    for (var transition : run.history()) {
      jdbc.sql(
              """
              INSERT INTO run_transitions
                (run_id, sequence_number, source_status, target_status, occurred_at, reason)
              VALUES
                (:runId, :sequence, :source, :target, :occurredAt, :reason)
              """)
          .param("runId", run.id().value())
          .param("sequence", transition.sequence())
          .param("source", transition.from().name())
          .param("target", transition.to().name())
          .param("occurredAt", Timestamp.from(transition.occurredAt()))
          .param("reason", transition.reason())
          .update();
    }
  }

  private List<StatusTransition> findHistory(RunId id) {
    return jdbc.sql(
            """
            SELECT sequence_number, source_status, target_status, occurred_at, reason
            FROM run_transitions
            WHERE run_id = :runId
            ORDER BY sequence_number
            """)
        .param("runId", id.value())
        .query(
            (resultSet, rowNumber) ->
                new StatusTransition(
                    resultSet.getInt("sequence_number"),
                    RunStatus.valueOf(resultSet.getString("source_status")),
                    RunStatus.valueOf(resultSet.getString("target_status")),
                    resultSet.getTimestamp("occurred_at").toInstant(),
                    resultSet.getString("reason")))
        .list();
  }

  private static String value(WorkspaceId workspaceId) {
    return workspaceId == null ? null : workspaceId.value();
  }

  private static String name(RunStatus status) {
    return status == null ? null : status.name();
  }

  private static WorkspaceId workspaceId(String value) {
    return value == null ? null : new WorkspaceId(value);
  }

  private static RunStatus status(String value) {
    return value == null ? null : RunStatus.valueOf(value);
  }
}

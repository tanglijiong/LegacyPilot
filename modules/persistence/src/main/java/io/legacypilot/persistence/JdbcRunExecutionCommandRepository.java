package io.legacypilot.persistence;

import io.legacypilot.application.execution.RunExecutionCommand;
import io.legacypilot.application.port.RunExecutionCommandRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcRunExecutionCommandRepository implements RunExecutionCommandRepository {
  private static final String COLUMNS =
      "id, run_id, operation, accepted_run_version, idempotency_key, actor, correlation_id, "
          + "status, attempts, owner, lease_expires_at, error_text, requested_at, updated_at";
  private final JdbcClient jdbc;

  public JdbcRunExecutionCommandRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public RunExecutionCommand add(RunExecutionCommand command) {
    jdbc.sql(
            """
            INSERT INTO run_execution_commands
              (id, run_id, operation, accepted_run_version, idempotency_key, actor,
               correlation_id, status, attempts, owner, lease_expires_at, error_text,
               requested_at, updated_at)
            VALUES
              (:id, :runId, :operation, :runVersion, :idempotencyKey, :actor,
               :correlationId, :status, :attempts, :owner, :leaseExpiresAt, :error,
               :requestedAt, :updatedAt)
            """)
        .param("id", command.id())
        .param("runId", command.runId())
        .param("operation", command.operation().name())
        .param("runVersion", command.acceptedRunVersion())
        .param("idempotencyKey", command.idempotencyKey())
        .param("actor", command.actor())
        .param("correlationId", command.correlationId())
        .param("status", command.status().name())
        .param("attempts", command.attempts())
        .param("owner", command.owner())
        .param("leaseExpiresAt", timestamp(command.leaseExpiresAt()))
        .param("error", command.error())
        .param("requestedAt", Timestamp.from(command.requestedAt()))
        .param("updatedAt", Timestamp.from(command.updatedAt()))
        .update();
    return command;
  }

  @Override
  public Optional<RunExecutionCommand> findById(String commandId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM run_execution_commands WHERE id = :id")
        .param("id", commandId)
        .query(JdbcRunExecutionCommandRepository::map)
        .optional();
  }

  @Override
  public Optional<RunExecutionCommand> findByRunAndIdempotencyKey(
      String runId, String idempotencyKey) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM run_execution_commands "
                + "WHERE run_id = :runId AND idempotency_key = :idempotencyKey")
        .param("runId", runId)
        .param("idempotencyKey", idempotencyKey)
        .query(JdbcRunExecutionCommandRepository::map)
        .optional();
  }

  @Override
  public Optional<RunExecutionCommand> findByRunOperationAndVersion(
      String runId, RunExecutionCommand.Operation operation, long runVersion) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM run_execution_commands "
                + "WHERE run_id = :runId AND operation = :operation "
                + "AND accepted_run_version = :runVersion")
        .param("runId", runId)
        .param("operation", operation.name())
        .param("runVersion", runVersion)
        .query(JdbcRunExecutionCommandRepository::map)
        .optional();
  }

  @Override
  public List<RunExecutionCommand> findByRun(String runId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM run_execution_commands WHERE run_id = :runId "
                + "ORDER BY requested_at, id")
        .param("runId", runId)
        .query(JdbcRunExecutionCommandRepository::map)
        .list();
  }

  @Override
  public List<RunExecutionCommand> findClaimable(Instant now, int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM run_execution_commands "
                + "WHERE status = 'QUEUED' OR (status = 'RUNNING' AND lease_expires_at < :now) "
                + "ORDER BY requested_at, id LIMIT :limit")
        .param("now", Timestamp.from(now))
        .param("limit", limit)
        .query(JdbcRunExecutionCommandRepository::map)
        .list();
  }

  @Override
  public boolean claim(String commandId, String owner, Instant now, Instant leaseExpiresAt) {
    return jdbc.sql(
                """
            UPDATE run_execution_commands
            SET status = 'RUNNING', attempts = attempts + 1, owner = :owner,
                lease_expires_at = :leaseExpiresAt, updated_at = :now, error_text = ''
            WHERE id = :id
              AND (status = 'QUEUED' OR (status = 'RUNNING' AND lease_expires_at < :now))
            """)
            .param("owner", owner)
            .param("leaseExpiresAt", Timestamp.from(leaseExpiresAt))
            .param("now", Timestamp.from(now))
            .param("id", commandId)
            .update()
        == 1;
  }

  @Override
  public boolean renew(String commandId, String owner, Instant now, Instant leaseExpiresAt) {
    return jdbc.sql(
                """
            UPDATE run_execution_commands
            SET lease_expires_at = :leaseExpiresAt, updated_at = :now
            WHERE id = :id AND owner = :owner AND status = 'RUNNING'
            """)
            .param("leaseExpiresAt", Timestamp.from(leaseExpiresAt))
            .param("now", Timestamp.from(now))
            .param("id", commandId)
            .param("owner", owner)
            .update()
        == 1;
  }

  @Override
  public void complete(String commandId, String owner, Instant now) {
    finish(commandId, owner, RunExecutionCommand.Status.COMPLETED, "", now);
  }

  @Override
  public void fail(String commandId, String owner, String error, Instant now) {
    finish(commandId, owner, RunExecutionCommand.Status.FAILED, error, now);
  }

  @Override
  public int cancelQueued(String runId, Instant now) {
    return jdbc.sql(
            """
            UPDATE run_execution_commands
            SET status = 'CANCELLED', updated_at = :now, error_text = 'cancelled by user'
            WHERE run_id = :runId AND status = 'QUEUED'
            """)
        .param("runId", runId)
        .param("now", Timestamp.from(now))
        .update();
  }

  private void finish(
      String commandId,
      String owner,
      RunExecutionCommand.Status status,
      String error,
      Instant now) {
    jdbc.sql(
            """
            UPDATE run_execution_commands
            SET status = :status, lease_expires_at = NULL, error_text = :error,
                updated_at = :now
            WHERE id = :id AND owner = :owner AND status = 'RUNNING'
            """)
        .param("status", status.name())
        .param("error", error)
        .param("now", Timestamp.from(now))
        .param("id", commandId)
        .param("owner", owner)
        .update();
  }

  private static RunExecutionCommand map(ResultSet resultSet, int rowNumber) throws SQLException {
    return new RunExecutionCommand(
        resultSet.getString("id"),
        resultSet.getString("run_id"),
        RunExecutionCommand.Operation.valueOf(resultSet.getString("operation")),
        resultSet.getLong("accepted_run_version"),
        resultSet.getString("idempotency_key"),
        resultSet.getString("actor"),
        resultSet.getString("correlation_id"),
        RunExecutionCommand.Status.valueOf(resultSet.getString("status")),
        resultSet.getInt("attempts"),
        resultSet.getString("owner"),
        instant(resultSet.getTimestamp("lease_expires_at")),
        resultSet.getString("error_text"),
        resultSet.getTimestamp("requested_at").toInstant(),
        resultSet.getTimestamp("updated_at").toInstant());
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }
}

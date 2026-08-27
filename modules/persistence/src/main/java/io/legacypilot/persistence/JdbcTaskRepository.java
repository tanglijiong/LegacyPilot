package io.legacypilot.persistence;

import io.legacypilot.application.port.TaskRepository;
import io.legacypilot.domain.project.ProjectId;
import io.legacypilot.domain.task.AcceptanceCriteria;
import io.legacypilot.domain.task.Requirement;
import io.legacypilot.domain.task.Task;
import io.legacypilot.domain.task.TaskId;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcTaskRepository implements TaskRepository {

  private final JdbcClient jdbc;

  public JdbcTaskRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void add(Task task) {
    jdbc.sql(
            """
            INSERT INTO tasks
              (id, project_id, requirement_text, acceptance_criteria, created_at)
            VALUES (:id, :projectId, :requirement, :criteria, :createdAt)
            """)
        .param("id", task.id().value())
        .param("projectId", task.projectId().value())
        .param("requirement", task.requirement().text())
        .param("criteria", encodeCriteria(task.acceptanceCriteria().items()))
        .param("createdAt", Timestamp.from(task.createdAt()))
        .update();
  }

  @Override
  public Optional<Task> findById(TaskId id) {
    return jdbc.sql(
            """
            SELECT id, project_id, requirement_text, acceptance_criteria, created_at
            FROM tasks
            WHERE id = :id
            """)
        .param("id", id.value())
        .query(
            (resultSet, rowNumber) ->
                new Task(
                    new TaskId(resultSet.getString("id")),
                    new ProjectId(resultSet.getString("project_id")),
                    new Requirement(resultSet.getString("requirement_text")),
                    new AcceptanceCriteria(
                        decodeCriteria(resultSet.getString("acceptance_criteria"))),
                    resultSet.getTimestamp("created_at").toInstant()))
        .optional();
  }

  private static String encodeCriteria(List<String> criteria) {
    return criteria.stream()
        .map(value -> Base64.getUrlEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)))
        .collect(java.util.stream.Collectors.joining("\n"));
  }

  private static List<String> decodeCriteria(String encoded) {
    if (encoded == null || encoded.isEmpty()) {
      return List.of();
    }
    return encoded
        .lines()
        .map(value -> new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8))
        .toList();
  }
}

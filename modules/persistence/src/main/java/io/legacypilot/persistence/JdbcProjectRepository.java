package io.legacypilot.persistence;

import io.legacypilot.application.port.ProjectRepository;
import io.legacypilot.domain.project.GitRevision;
import io.legacypilot.domain.project.Project;
import io.legacypilot.domain.project.ProjectId;
import io.legacypilot.domain.project.RepositoryLocation;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcProjectRepository implements ProjectRepository {

  private final JdbcClient jdbc;

  public JdbcProjectRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void add(Project project) {
    try {
      jdbc.sql(
              """
              INSERT INTO projects
                (id, source, repository_path, base_revision, registered_at)
              VALUES (:id, :source, :repositoryPath, :baseRevision, :registeredAt)
              """)
          .param("id", project.id().value())
          .param("source", project.source().value())
          .param("repositoryPath", project.repositoryPath())
          .param("baseRevision", project.baseRevision().value())
          .param("registeredAt", Timestamp.from(project.registeredAt()))
          .update();
    } catch (DuplicateKeyException exception) {
      throw new IllegalStateException("Project already exists: " + project.id().value(), exception);
    }
  }

  @Override
  public Optional<Project> findById(ProjectId id) {
    return jdbc.sql(
            """
            SELECT id, source, repository_path, base_revision, registered_at
            FROM projects
            WHERE id = :id
            """)
        .param("id", id.value())
        .query(
            (resultSet, rowNumber) ->
                new Project(
                    new ProjectId(resultSet.getString("id")),
                    new RepositoryLocation(resultSet.getString("source")),
                    resultSet.getString("repository_path"),
                    new GitRevision(resultSet.getString("base_revision")),
                    resultSet.getTimestamp("registered_at").toInstant()))
        .optional();
  }
}

package io.legacypilot.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.legacypilot.application.error.ResourceNotFoundException;
import io.legacypilot.application.error.SourceAccessException;
import io.legacypilot.application.port.ProjectRepository;
import io.legacypilot.application.port.SourceFileReader;
import io.legacypilot.application.port.SourceFileReader.SourceReadRequest;
import io.legacypilot.application.service.ReadProjectSourceUseCase;
import io.legacypilot.domain.project.GitRevision;
import io.legacypilot.domain.project.Project;
import io.legacypilot.domain.project.ProjectId;
import io.legacypilot.domain.project.RepositoryLocation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReadProjectSourceUseCaseTest {

  private static final String REVISION = "a".repeat(40);
  private static final Project PROJECT =
      new Project(
          new ProjectId("project-1"),
          new RepositoryLocation("/repo"),
          "/repo",
          new GitRevision(REVISION),
          Instant.EPOCH);

  @Test
  void requiresAnExistingProjectAndItsExactPinnedRevision() {
    var reader = new CapturingReader();
    var useCase = new ReadProjectSourceUseCase(new SingleProjectRepository(), reader);
    var request = new SourceReadRequest(REVISION.toUpperCase(), "source.java", 1, 2, 3, false);

    assertEquals("project-1", useCase.read("project-1", request).projectId());
    assertEquals(PROJECT, reader.project);

    var mismatch =
        assertThrows(
            SourceAccessException.class,
            () ->
                useCase.read(
                    "project-1",
                    new SourceReadRequest("b".repeat(40), "source.java", 1, 1, 0, false)));
    assertEquals(SourceAccessException.Reason.REVISION_MISMATCH, mismatch.reason());
    assertThrows(ResourceNotFoundException.class, () -> useCase.read("missing", request));
  }

  private static final class CapturingReader implements SourceFileReader {
    private Project project;

    @Override
    public SourceSnippet read(Project project, SourceReadRequest request) {
      this.project = project;
      return new SourceSnippet(
          project.id().value(), REVISION, request.path(), 1, 1, 1, "source", "a", "b", "c", false);
    }
  }

  private static final class SingleProjectRepository implements ProjectRepository {
    @Override
    public void add(Project project) {}

    @Override
    public Optional<Project> findById(ProjectId id) {
      return id.equals(PROJECT.id()) ? Optional.of(PROJECT) : Optional.empty();
    }

    @Override
    public List<Project> findAll() {
      return List.of(PROJECT);
    }

    @Override
    public void remove(ProjectId id) {}
  }
}

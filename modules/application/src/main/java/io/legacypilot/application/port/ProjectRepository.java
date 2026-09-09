package io.legacypilot.application.port;

import io.legacypilot.domain.project.Project;
import io.legacypilot.domain.project.ProjectId;
import java.util.List;
import java.util.Optional;

public interface ProjectRepository {
  void add(Project project);

  Optional<Project> findById(ProjectId id);

  List<Project> findAll();

  void remove(ProjectId id);
}

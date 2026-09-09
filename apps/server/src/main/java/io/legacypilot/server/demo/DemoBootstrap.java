package io.legacypilot.server.demo;

import io.legacypilot.application.service.ListProjectsUseCase;
import io.legacypilot.application.service.RegisterProjectUseCase;
import io.legacypilot.domain.project.Project;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
public final class DemoBootstrap implements ApplicationRunner {
  private final RegisterProjectUseCase registerProject;
  private final ListProjectsUseCase listProjects;
  private final Path source;
  private final String revision;
  private volatile Project project;

  public DemoBootstrap(
      RegisterProjectUseCase registerProject,
      ListProjectsUseCase listProjects,
      @Value("${legacy-pilot.demo.source}") Path source,
      @Value("${legacy-pilot.demo.revision:HEAD}") String revision) {
    this.registerProject = registerProject;
    this.listProjects = listProjects;
    this.source = realPath(source);
    this.revision = revision;
  }

  @Override
  public void run(ApplicationArguments arguments) {
    project =
        listProjects.list().stream()
            .filter(candidate -> sameSource(candidate.source().value()))
            .findFirst()
            .orElseGet(() -> registerProject.register(source.toString(), revision));
  }

  public Project project() {
    if (project == null) {
      throw new IllegalStateException("Banking Demo project has not been initialized");
    }
    return project;
  }

  private boolean sameSource(String candidate) {
    try {
      return source.equals(Path.of(candidate).toRealPath());
    } catch (IOException exception) {
      return false;
    }
  }

  private static Path realPath(Path value) {
    try {
      return value.toAbsolutePath().normalize().toRealPath();
    } catch (IOException exception) {
      throw new IllegalStateException("Banking Demo source is missing or inaccessible", exception);
    }
  }
}

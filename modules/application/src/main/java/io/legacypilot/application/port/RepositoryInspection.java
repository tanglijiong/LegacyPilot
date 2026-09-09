package io.legacypilot.application.port;

import io.legacypilot.domain.project.GitRevision;
import java.util.List;

/** Read-only facts collected before a repository is registered. */
public record RepositoryInspection(
    String source,
    String repositoryPath,
    GitRevision revision,
    String sourceKind,
    boolean clean,
    List<String> technologies,
    List<String> capabilities,
    List<String> warnings) {

  public RepositoryInspection {
    source = java.util.Objects.requireNonNull(source);
    technologies = List.copyOf(technologies);
    capabilities = List.copyOf(capabilities);
    warnings = List.copyOf(warnings);
  }
}

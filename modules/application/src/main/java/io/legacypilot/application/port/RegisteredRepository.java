package io.legacypilot.application.port;

import io.legacypilot.domain.project.GitRevision;

public record RegisteredRepository(String repositoryPath, GitRevision revision) {}

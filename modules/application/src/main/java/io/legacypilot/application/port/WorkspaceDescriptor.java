package io.legacypilot.application.port;

import io.legacypilot.domain.run.WorkspaceId;

public record WorkspaceDescriptor(WorkspaceId id, String path) {}

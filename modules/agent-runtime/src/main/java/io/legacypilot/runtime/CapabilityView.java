package io.legacypilot.runtime;

import java.time.Instant;

public record CapabilityView(
    String id,
    String subject,
    String sessionId,
    String runId,
    String tool,
    String workspace,
    String actionDigest,
    String planDigest,
    Instant expiresAt,
    int maximumUses,
    int uses,
    boolean revoked,
    Instant updatedAt) {}

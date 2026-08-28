package io.legacypilot.runtime;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

public record CapabilityGrant(
    String id,
    String tokenDigest,
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
    Instant updatedAt) {

  public CapabilityGrant {
    Objects.requireNonNull(id);
    Objects.requireNonNull(tokenDigest);
    Objects.requireNonNull(subject);
    Objects.requireNonNull(sessionId);
    Objects.requireNonNull(runId);
    Objects.requireNonNull(tool);
    Objects.requireNonNull(workspace);
    Objects.requireNonNull(actionDigest);
    planDigest = Objects.requireNonNullElse(planDigest, "");
    Objects.requireNonNull(expiresAt);
    Objects.requireNonNull(updatedAt);
    if (!id.matches("cap-[a-f0-9]{24}") || !tokenDigest.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("capability identity is invalid");
    }
    if (maximumUses < 1 || uses < 0 || uses > maximumUses) {
      throw new IllegalArgumentException("capability usage is invalid");
    }
  }

  public boolean matches(CapabilityUse use, Instant now) {
    return !revoked
        && now.isBefore(expiresAt)
        && uses < maximumUses
        && subject.equals(use.subject())
        && sessionId.equals(use.sessionId())
        && runId.equals(use.runId())
        && tool.equals(use.tool())
        && Path.of(workspace).equals(use.workspace())
        && actionDigest.equals(use.actionDigest())
        && (planDigest.isBlank() || planDigest.equals(use.planDigest()));
  }

  public CapabilityGrant consumed(Instant now) {
    return new CapabilityGrant(
        id,
        tokenDigest,
        subject,
        sessionId,
        runId,
        tool,
        workspace,
        actionDigest,
        planDigest,
        expiresAt,
        maximumUses,
        uses + 1,
        revoked,
        now);
  }

  public CapabilityGrant revoke(Instant now) {
    return new CapabilityGrant(
        id,
        tokenDigest,
        subject,
        sessionId,
        runId,
        tool,
        workspace,
        actionDigest,
        planDigest,
        expiresAt,
        maximumUses,
        uses,
        true,
        now);
  }

  public CapabilityView view() {
    return new CapabilityView(
        id,
        subject,
        sessionId,
        runId,
        tool,
        workspace,
        actionDigest,
        planDigest,
        expiresAt,
        maximumUses,
        uses,
        revoked,
        updatedAt);
  }
}

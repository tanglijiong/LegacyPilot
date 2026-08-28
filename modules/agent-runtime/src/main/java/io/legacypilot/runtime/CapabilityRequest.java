package io.legacypilot.runtime;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

public record CapabilityRequest(
    String subject,
    String sessionId,
    String runId,
    String tool,
    Path workspace,
    String actionDigest,
    String planDigest,
    Instant expiresAt,
    int maximumUses) {
  public CapabilityRequest {
    subject = requireValue(subject, "subject");
    sessionId = requireId(sessionId, "session id");
    runId = requireId(runId, "run id");
    tool = requireTool(tool);
    workspace =
        Objects.requireNonNull(workspace, "workspace must not be null")
            .toAbsolutePath()
            .normalize();
    actionDigest = requireDigest(actionDigest, "action digest");
    planDigest =
        planDigest == null || planDigest.isBlank() ? "" : requireDigest(planDigest, "plan digest");
    Objects.requireNonNull(expiresAt, "expiry must not be null");
    if (maximumUses < 1 || maximumUses > 100) {
      throw new IllegalArgumentException("capability maximum uses must be between 1 and 100");
    }
  }

  private static String requireValue(String value, String name) {
    if (value == null || value.isBlank() || value.length() > 160) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return value;
  }

  private static String requireDigest(String value, String name) {
    if (value == null || !value.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return value;
  }

  private static String requireId(String value, String name) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return value;
  }

  private static String requireTool(String value) {
    if (value == null || !value.matches("[a-z][a-z0-9_.-]{1,95}")) {
      throw new IllegalArgumentException("tool is invalid");
    }
    return value;
  }
}

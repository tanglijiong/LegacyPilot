package io.legacypilot.server.security;

import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public final class RunSecurityContextStore {
  private final JdbcClient jdbc;

  public RunSecurityContextStore(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  public void recordInitiator(String runId, String actor) {
    requireActor(actor);
    var existing = initiator(runId);
    if (existing.isPresent()) {
      if (!existing.get().equals(actor)) {
        throw new IllegalStateException("run execution was initiated by a different actor");
      }
      return;
    }
    jdbc.sql("INSERT INTO run_security_context(run_id, initiated_by) VALUES (:runId, :actor)")
        .param("runId", runId)
        .param("actor", actor)
        .update();
  }

  public Optional<String> initiator(String runId) {
    return jdbc.sql("SELECT initiated_by FROM run_security_context WHERE run_id = :runId")
        .param("runId", runId)
        .query(String.class)
        .optional();
  }

  public void requireIndependentApprover(String runId, String actor) {
    requireActor(actor);
    var initiator =
        initiator(runId)
            .orElseThrow(() -> new IllegalStateException("run initiator is not recorded"));
    if (initiator.equals(actor)) {
      throw new IllegalStateException("run initiator cannot approve the same run");
    }
  }

  private static void requireActor(String actor) {
    if (actor == null || actor.isBlank() || actor.length() > 160) {
      throw new IllegalArgumentException("authenticated actor is invalid");
    }
  }
}

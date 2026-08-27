package io.legacypilot.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.legacypilot.domain.project.GitRevision;
import io.legacypilot.domain.project.Project;
import io.legacypilot.domain.project.ProjectId;
import io.legacypilot.domain.project.RepositoryLocation;
import io.legacypilot.domain.run.Approval;
import io.legacypilot.domain.run.Budget;
import io.legacypilot.domain.run.InvalidStateTransitionException;
import io.legacypilot.domain.run.Plan;
import io.legacypilot.domain.run.RunId;
import io.legacypilot.domain.run.RunStatus;
import io.legacypilot.domain.run.StatusTransition;
import io.legacypilot.domain.run.TaskRun;
import io.legacypilot.domain.run.VerificationResult;
import io.legacypilot.domain.run.WorkspaceId;
import io.legacypilot.domain.task.AcceptanceCriteria;
import io.legacypilot.domain.task.Requirement;
import io.legacypilot.domain.task.Task;
import io.legacypilot.domain.task.TaskId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DomainModelTest {

  private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");
  private static final String REVISION = "ABCDEF0123456789ABCDEF0123456789ABCDEF01";

  @Test
  void modelsProjectAndTaskWithImmutableValues() {
    var project =
        new Project(
            new ProjectId("project-1"),
            new RepositoryLocation("/repo"),
            "/repo",
            new GitRevision(REVISION),
            NOW);
    var criteria = new AcceptanceCriteria(List.of("tests pass", "source stays clean"));
    var task =
        new Task(
            new TaskId("task-1"), project.id(), new Requirement("add a feature"), criteria, NOW);

    assertEquals(REVISION.toLowerCase(java.util.Locale.ROOT), project.baseRevision().value());
    assertEquals("add a feature", task.requirement().text());
    assertEquals(2, task.acceptanceCriteria().items().size());
    assertTrue(AcceptanceCriteria.none().items().isEmpty());
  }

  @Test
  void rejectsInvalidValueObjects() {
    assertThrows(IllegalArgumentException.class, () -> new GitRevision("short"));
    assertThrows(IllegalArgumentException.class, () -> new RepositoryLocation(" "));
    assertThrows(IllegalArgumentException.class, () -> new TaskId(" "));
    assertThrows(IllegalArgumentException.class, () -> new RunId(" "));
    assertThrows(IllegalArgumentException.class, () -> new WorkspaceId(" "));
    assertThrows(IllegalArgumentException.class, () -> new Requirement(" "));
    assertThrows(
        IllegalArgumentException.class, () -> new AcceptanceCriteria(List.of("valid", " ")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Project(
                new ProjectId("p"),
                new RepositoryLocation("/repo"),
                " ",
                new GitRevision(REVISION),
                NOW));
  }

  @Test
  void followsTheHappyPathStateMachineAndPreservesHistory() {
    var run = TaskRun.create(new RunId("run-1"), new TaskId("task-1"), NOW);
    run = run.transitionTo(RunStatus.PREPARING_WORKSPACE, NOW.plusSeconds(1), "started");
    run = run.workspaceReady(new WorkspaceId("run-1"), NOW.plusSeconds(2));
    run = run.transitionTo(RunStatus.PLANNING, NOW.plusSeconds(3), "planning");
    run = run.transitionTo(RunStatus.EXECUTING, NOW.plusSeconds(4), "approved implicitly");
    run = run.transitionTo(RunStatus.VERIFYING, NOW.plusSeconds(5), "verify");
    run = run.transitionTo(RunStatus.SUCCEEDED, NOW.plusSeconds(6), "checks passed");
    var terminalRun = run;

    assertEquals(RunStatus.SUCCEEDED, run.status());
    assertTrue(run.status().isTerminal());
    assertEquals("run-1", run.workspaceId().value());
    assertEquals("checks passed", run.terminalReason());
    assertEquals(6, run.history().size());
    assertEquals(NOW, run.createdAt());
    assertEquals(NOW.plusSeconds(6), run.updatedAt());
    assertEquals(7, run.withVersion(7).version());
    assertThrows(
        InvalidStateTransitionException.class,
        () -> terminalRun.transitionTo(RunStatus.EXECUTING, NOW.plusSeconds(7), "too late"));
  }

  @Test
  void recoversToThePreviousState() {
    var run = TaskRun.create(new RunId("run-2"), new TaskId("task-2"), NOW);
    run = run.transitionTo(RunStatus.PREPARING_WORKSPACE, NOW, null);
    run = run.beginRecovery(NOW.plusSeconds(1), "restart");

    assertEquals(RunStatus.PREPARING_WORKSPACE, run.recoveryTarget());
    var recovered = run.recover(NOW.plusSeconds(2));
    assertEquals(RunStatus.PREPARING_WORKSPACE, recovered.status());
    assertNull(recovered.recoveryTarget());
    assertThrows(IllegalStateException.class, () -> recovered.recover(NOW));
  }

  @Test
  void restoresPersistedRunAndRejectsInvalidTransitions() {
    var transition = new StatusTransition(1, RunStatus.CREATED, RunStatus.CANCELLED, NOW, null);
    var restored =
        TaskRun.restore(
            new RunId("run-3"),
            new TaskId("task-3"),
            RunStatus.CANCELLED,
            4,
            null,
            null,
            "cancelled",
            NOW,
            NOW,
            List.of(transition));

    assertEquals(4, restored.version());
    assertEquals("", restored.history().getFirst().reason());
    assertThrows(IllegalArgumentException.class, () -> restored.withVersion(-1));
    assertThrows(
        InvalidStateTransitionException.class,
        () -> restored.beginRecovery(NOW.plusSeconds(1), "invalid"));
    var created = TaskRun.create(new RunId("created"), new TaskId("task"), NOW);
    assertThrows(
        InvalidStateTransitionException.class,
        () -> created.workspaceReady(new WorkspaceId("created"), NOW));
  }

  @Test
  void evaluatesPlanningApprovalBudgetAndVerificationValues() {
    var budget = new Budget(10, 2, Duration.ofMinutes(5));
    var plan = new Plan(1, List.of("inspect", "change"), List.of("orders"), "low");
    var approval =
        new Approval("digest", Approval.Decision.APPROVED, "reviewer", null, NOW.plusSeconds(30));
    var successful =
        new VerificationResult(
            List.of(
                new VerificationResult.Check("tests", VerificationResult.Status.PASSED, true, "ok"),
                new VerificationResult.Check(
                    "optional", VerificationResult.Status.SKIPPED, false, null)));
    var failed =
        new VerificationResult(
            List.of(
                new VerificationResult.Check(
                    "tests", VerificationResult.Status.FAILED, true, "failed")));

    assertEquals(10, budget.maxSteps());
    assertEquals(2, plan.steps().size());
    assertEquals("", approval.reason());
    assertTrue(successful.successful());
    assertFalse(failed.successful());
    assertNotNull(approval.expiresAt());
    assertThrows(IllegalArgumentException.class, () -> new Budget(0, -1, Duration.ofSeconds(-1)));
    assertThrows(IllegalArgumentException.class, () -> new Plan(0, List.of(), List.of(), "risk"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StatusTransition(0, RunStatus.CREATED, RunStatus.CANCELLED, NOW, ""));
  }
}

package io.legacypilot.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.legacypilot.application.error.ConcurrentUpdateException;
import io.legacypilot.application.execution.RunExecutionCommand;
import io.legacypilot.domain.project.GitRevision;
import io.legacypilot.domain.project.Project;
import io.legacypilot.domain.project.ProjectId;
import io.legacypilot.domain.project.RepositoryLocation;
import io.legacypilot.domain.run.RunId;
import io.legacypilot.domain.run.RunStatus;
import io.legacypilot.domain.run.TaskRun;
import io.legacypilot.domain.run.WorkspaceId;
import io.legacypilot.domain.task.AcceptanceCriteria;
import io.legacypilot.domain.task.Requirement;
import io.legacypilot.domain.task.Task;
import io.legacypilot.domain.task.TaskId;
import io.legacypilot.domain.task.TaskMode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcRepositoriesTest {

  private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

  private JdbcProjectRepository projects;
  private JdbcTaskRepository tasks;
  private JdbcTaskRunRepository runs;
  private JdbcRunExecutionCommandRepository commands;

  @TempDir Path temporaryDirectory;

  @BeforeEach
  void setUp() {
    var dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:test-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    Flyway.configure().dataSource(dataSource).load().migrate();
    var jdbc = JdbcClient.create(dataSource);
    projects = new JdbcProjectRepository(jdbc);
    tasks = new JdbcTaskRepository(jdbc);
    runs = new JdbcTaskRunRepository(jdbc);
    commands = new JdbcRunExecutionCommandRepository(jdbc);
  }

  @Test
  void roundTripsAllLifecycleAggregatesAndTransitionHistory() {
    var project =
        new Project(
            new ProjectId("project-1"),
            new RepositoryLocation("/source"),
            "/source",
            new GitRevision("0123456789012345678901234567890123456789"),
            NOW);
    var task =
        new Task(
            new TaskId("task-1"),
            project.id(),
            TaskMode.EXPLAIN,
            new Requirement("support unicode 数据"),
            new AcceptanceCriteria(List.of("first\nline", "第二项")),
            NOW);
    projects.add(project);
    tasks.add(task);
    var run = runs.add(TaskRun.create(new RunId("run-1"), task.id(), NOW));
    run = runs.update(run.transitionTo(RunStatus.PREPARING_WORKSPACE, NOW, "start"));
    run = runs.update(run.workspaceReady(new WorkspaceId("run-1"), NOW));

    assertEquals(project, projects.findById(project.id()).orElseThrow());
    assertEquals(task, tasks.findById(task.id()).orElseThrow());
    assertEquals(run.status(), runs.findById(run.id()).orElseThrow().status());
    assertEquals(List.of(project), projects.findAll());
    assertEquals(List.of(task), tasks.findByProjectId(project.id()));
    assertEquals(run.status(), runs.findLatestByTaskId(task.id()).orElseThrow().status());
    assertEquals(2, runs.findById(run.id()).orElseThrow().history().size());
    assertTrue(projects.findById(new ProjectId("missing")).isEmpty());
    assertTrue(tasks.findById(new TaskId("missing")).isEmpty());
  }

  @Test
  void rejectsAStaleOptimisticUpdate() {
    var task = seedTask();
    var run = runs.add(TaskRun.create(new RunId("run-lock"), task.id(), NOW));
    runs.update(run.transitionTo(RunStatus.PREPARING_WORKSPACE, NOW, "first"));

    assertThrows(
        ConcurrentUpdateException.class,
        () -> runs.update(run.transitionTo(RunStatus.CANCELLED, NOW, "stale")));
  }

  @Test
  void rejectsDuplicateProjectIds() {
    var project = seedProject();
    assertThrows(IllegalStateException.class, () -> projects.add(project));
  }

  @Test
  void softRemovesAProjectFromActiveQueries() {
    var project = seedProject();

    projects.remove(project.id());

    assertTrue(projects.findById(project.id()).isEmpty());
    assertEquals(List.of(), projects.findAll());
  }

  @Test
  void claimsAndRenewsDurableExecutionCommandsOnce() {
    var task = seedTask();
    runs.add(TaskRun.create(new RunId("run-command"), task.id(), NOW));
    var command =
        new RunExecutionCommand(
            "command-1",
            "run-command",
            RunExecutionCommand.Operation.EXECUTE,
            0,
            "execute-run-command",
            "developer-a",
            "correlation-1",
            RunExecutionCommand.Status.QUEUED,
            0,
            null,
            null,
            "",
            NOW,
            NOW);
    commands.add(command);

    assertEquals(command, commands.findById(command.id()).orElseThrow());
    assertEquals(List.of(command), commands.findByRun("run-command"));
    assertTrue(commands.claim(command.id(), "worker-a", NOW, NOW.plusSeconds(120)));
    assertFalse(commands.claim(command.id(), "worker-b", NOW, NOW.plusSeconds(120)));
    assertTrue(commands.renew(command.id(), "worker-a", NOW, NOW.plusSeconds(180)));
    assertEquals(1, commands.findClaimable(NOW.plusSeconds(181), 10).size());

    assertTrue(
        commands.claim(command.id(), "worker-b", NOW.plusSeconds(181), NOW.plusSeconds(300)));
    commands.complete(command.id(), "worker-b", NOW.plusSeconds(182));
    assertEquals(
        RunExecutionCommand.Status.COMPLETED,
        commands.findById(command.id()).orElseThrow().status());
    assertEquals(2, commands.findById(command.id()).orElseThrow().attempts());
  }

  @Test
  void cancelsQueuedCommandsWithoutDiscardingTheirAuditRecord() {
    var task = seedTask();
    runs.add(TaskRun.create(new RunId("run-cancel-command"), task.id(), NOW));
    var command =
        new RunExecutionCommand(
            "command-cancel",
            "run-cancel-command",
            RunExecutionCommand.Operation.EXECUTE,
            0,
            "execute-run-cancel-command",
            "developer-a",
            "correlation-cancel",
            RunExecutionCommand.Status.QUEUED,
            0,
            null,
            null,
            "",
            NOW,
            NOW);
    commands.add(command);

    assertEquals(1, commands.cancelQueued("run-cancel-command", NOW.plusSeconds(1)));
    assertEquals(
        RunExecutionCommand.Status.CANCELLED,
        commands.findById(command.id()).orElseThrow().status());
  }

  @Test
  void survivesAFileDatabaseRestartAndRepeatedMigration() {
    var url = "jdbc:h2:file:" + temporaryDirectory.resolve("lifecycle");
    var first = dataSource(url);
    Flyway.configure().dataSource(first).load().migrate();
    var project =
        new Project(
            new ProjectId("durable"),
            new RepositoryLocation("/durable"),
            "/durable",
            new GitRevision("0123456789012345678901234567890123456789"),
            NOW);
    new JdbcProjectRepository(JdbcClient.create(first)).add(project);

    var restarted = dataSource(url);
    assertEquals(0, Flyway.configure().dataSource(restarted).load().migrate().migrationsExecuted);
    assertEquals(
        project,
        new JdbcProjectRepository(JdbcClient.create(restarted))
            .findById(project.id())
            .orElseThrow());
  }

  @Test
  void upgradesAReleasedV02DatabaseWithoutChangingTheV1Checksum() {
    var dataSource = dataSource("jdbc:h2:mem:v02-upgrade;DB_CLOSE_DELAY=-1");
    assertEquals(
        1,
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/v0.2")
            .load()
            .migrate()
            .migrationsExecuted);

    assertEquals(5, Flyway.configure().dataSource(dataSource).load().migrate().migrationsExecuted);
    assertEquals(
        0,
        JdbcClient.create(dataSource)
            .sql("SELECT COUNT(*) FROM run_security_context")
            .query(Integer.class)
            .single());
    assertEquals(
        1,
        JdbcClient.create(dataSource)
            .sql(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                    + "WHERE TABLE_NAME = 'TASKS' AND COLUMN_NAME = 'TASK_MODE'")
            .query(Integer.class)
            .single());
    assertEquals(
        1,
        JdbcClient.create(dataSource)
            .sql(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                    + "WHERE TABLE_NAME = 'TASKS' AND COLUMN_NAME = 'CONVERSATION_ID'")
            .query(Integer.class)
            .single());
  }

  private Project seedProject() {
    var project =
        new Project(
            new ProjectId("project-seed"),
            new RepositoryLocation("/source"),
            "/source",
            new GitRevision("0123456789012345678901234567890123456789"),
            NOW);
    projects.add(project);
    return project;
  }

  private Task seedTask() {
    var project = seedProject();
    var task =
        new Task(
            new TaskId("task-seed"),
            project.id(),
            new Requirement("work"),
            AcceptanceCriteria.none(),
            NOW);
    tasks.add(task);
    return task;
  }

  private static JdbcDataSource dataSource(String url) {
    var result = new JdbcDataSource();
    result.setURL(url);
    return result;
  }
}

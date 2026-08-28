package io.legacypilot.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.application.service.CancelRunUseCase;
import io.legacypilot.application.service.CreateTaskUseCase;
import io.legacypilot.application.service.GetProjectUseCase;
import io.legacypilot.application.service.GetRunStatusUseCase;
import io.legacypilot.application.service.GetTaskUseCase;
import io.legacypilot.application.service.RegisterProjectUseCase;
import io.legacypilot.application.service.StartRunUseCase;
import io.legacypilot.domain.project.GitRevision;
import io.legacypilot.domain.project.Project;
import io.legacypilot.domain.project.ProjectId;
import io.legacypilot.domain.project.RepositoryLocation;
import io.legacypilot.domain.run.RunId;
import io.legacypilot.domain.run.TaskRun;
import io.legacypilot.domain.task.AcceptanceCriteria;
import io.legacypilot.domain.task.Requirement;
import io.legacypilot.domain.task.Task;
import io.legacypilot.domain.task.TaskId;
import io.legacypilot.model.ModelUsage;
import io.legacypilot.observability.InMemoryTraceSink;
import io.legacypilot.observability.SensitiveDataRedactor;
import io.legacypilot.runtime.AgentCheckpoint;
import io.legacypilot.runtime.AgentRuntime;
import io.legacypilot.runtime.AgentRuntimeResult;
import io.legacypilot.runtime.ApprovalStore;
import io.legacypilot.runtime.CapabilityRequest;
import io.legacypilot.runtime.CapabilityService;
import io.legacypilot.runtime.InMemoryCapabilityGrantStore;
import io.legacypilot.runtime.RecoveryCoordinator;
import io.legacypilot.runtime.RuntimeApproval;
import io.legacypilot.runtime.RuntimeStatus;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CliCommandsTest {

  private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");
  @TempDir Path temporary;

  @Test
  void executesProjectAndTaskCommandsAsJson() {
    var output = output();
    var project = project();
    var task = task();
    var register = mock(RegisterProjectUseCase.class);
    var getProject = mock(GetProjectUseCase.class);
    var createTask = mock(CreateTaskUseCase.class);
    var getTask = mock(GetTaskUseCase.class);
    when(register.register("/repo", "HEAD")).thenReturn(project);
    when(getProject.get("project-1")).thenReturn(project);
    when(createTask.create("project-1", "work", List.of("tests"))).thenReturn(task);
    when(getTask.get("task-1")).thenReturn(task);

    var text =
        capture(
            () -> {
              assertEquals(
                  0,
                  new CommandLine(new ProjectRegisterCommand(register, output))
                      .execute("--source", "/repo", "--revision", "HEAD"));
              assertEquals(
                  0,
                  new CommandLine(new ProjectShowCommand(getProject, output)).execute("project-1"));
              assertEquals(
                  0,
                  new CommandLine(new TaskCreateCommand(createTask, output))
                      .execute(
                          "--project",
                          "project-1",
                          "--requirement",
                          "work",
                          "--criterion",
                          "tests"));
              assertEquals(
                  0, new CommandLine(new TaskShowCommand(getTask, output)).execute("task-1"));
            });
    assertTrue(text.contains("\"id\":{\"value\":\"project-1\"}"));
    assertTrue(text.contains("\"requirement\":{\"text\":\"work\"}"));
  }

  @Test
  void executesRunCommandsAsJson() {
    var output = output();
    var run = TaskRun.create(new RunId("run-1"), new TaskId("task-1"), NOW);
    var start = mock(StartRunUseCase.class);
    var status = mock(GetRunStatusUseCase.class);
    var cancel = mock(CancelRunUseCase.class);
    when(start.start("task-1")).thenReturn(run);
    when(status.get("run-1")).thenReturn(run);
    when(cancel.cancel("run-1")).thenReturn(run);

    var text =
        capture(
            () -> {
              assertEquals(
                  0, new CommandLine(new RunStartCommand(start, output)).execute("task-1"));
              assertEquals(
                  0, new CommandLine(new RunStatusCommand(status, output)).execute("run-1"));
              assertEquals(
                  0, new CommandLine(new RunCancelCommand(cancel, output)).execute("run-1"));
            });
    assertTrue(text.contains("\"status\":\"CREATED\""));
  }

  @Test
  void rootCommandRequiresASubcommand() {
    assertThrows(CommandLine.ParameterException.class, () -> new LegacyPilotCommand().run());
    assertNotNull(new LegacyPilotCliApplication().legacyPilotObjectMapper());
  }

  @Test
  void inspectsMissingAndUnsupportedAgentState() throws Exception {
    var command = new AgentStateCheckCommand(new ObjectMapper().findAndRegisterModules());
    var missing =
        captureResult(
            () -> new CommandLine(command).execute("run-1", "--state-root", temporary.toString()));
    assertEquals(0, missing.exitCode());
    assertTrue(missing.text().contains("MISSING"));

    var checkpoint = temporary.resolve("checkpoints/run-2.json");
    java.nio.file.Files.createDirectories(checkpoint.getParent());
    java.nio.file.Files.writeString(checkpoint, "{\"schemaVersion\":99,\"payload\":{}}");
    var unsupported =
        captureResult(
            () ->
                new CommandLine(
                        new AgentStateCheckCommand(new ObjectMapper().findAndRegisterModules()))
                    .execute("run-2", "--state-root", temporary.toString()));
    assertEquals(2, unsupported.exitCode());
    assertTrue(unsupported.text().contains("UNSUPPORTED"));
  }

  @Test
  void runsSafeRecoveryScan() {
    var recovery = mock(RecoveryCoordinator.class);
    when(recovery.recoverAll()).thenReturn(List.of());

    var text =
        capture(
            () ->
                assertEquals(
                    0, new CommandLine(new AgentRecoverCommand(recovery, output())).execute()));

    assertEquals("[]" + System.lineSeparator(), text);
    verify(recovery).recoverAll();
  }

  @Test
  void submitsApprovalAndResumesPersistedAgentRun() {
    var output = output();
    var approvals = mock(ApprovalStore.class);
    var runtime = mock(AgentRuntime.class);
    var checkpoint =
        new AgentCheckpoint(
            "agent-1",
            RuntimeStatus.WAITING_FOR_APPROVAL,
            null,
            0,
            0,
            ModelUsage.NONE,
            NOW,
            NOW,
            null,
            "",
            0,
            "waiting");
    when(runtime.resume("agent-1")).thenReturn(new AgentRuntimeResult(checkpoint, null, null));

    var text =
        capture(
            () -> {
              assertEquals(
                  0,
                  new CommandLine(new AgentApproveCommand(approvals, output))
                      .execute(
                          "agent-1",
                          "--action-digest",
                          "action-hash",
                          "--plan-digest",
                          "plan-hash",
                          "--actor",
                          "reviewer",
                          "--decision",
                          "DENIED"));
              assertEquals(
                  0, new CommandLine(new AgentResumeCommand(runtime, output)).execute("agent-1"));
            });

    verify(approvals).save(org.mockito.ArgumentMatchers.any(RuntimeApproval.class));
    verify(runtime).resume("agent-1");
    assertTrue(text.contains("DENIED"));
    assertTrue(text.contains("WAITING_FOR_APPROVAL"));
  }

  @Test
  void runsTheFiveTaskReferenceEvalWithOneCommand() {
    var root = Path.of("../..").toAbsolutePath().normalize();
    var text =
        capture(
            () ->
                assertEquals(
                    0,
                    new CommandLine(new EvalRunCommand(output()))
                        .execute(
                            "--dataset",
                            root.resolve("evals/datasets/v0.1").toString(),
                            "--fixture",
                            root.resolve("samples/banking-demo").toString(),
                            "--references",
                            root.resolve("evals/reference-solutions").toString(),
                            "--maven-wrapper",
                            root.resolve("mvnw").toString(),
                            "--concurrency",
                            "2")));
    assertTrue(text.contains("reference-ceiling"));
    assertTrue(text.contains("\"status\":\"PASSED\""));
  }

  @Test
  void issuesAndRevokesScopedCapabilitiesWithoutPrintingStoredTokenDigests() {
    var clock = Clock.fixed(NOW, ZoneOffset.UTC);
    var service =
        new CapabilityService(
            new InMemoryCapabilityGrantStore(),
            new InMemoryTraceSink(new SensitiveDataRedactor(8_192)),
            clock);
    var digest = "a".repeat(64);
    var issuedText =
        capture(
            () ->
                assertEquals(
                    0,
                    new CommandLine(new CapabilityIssueCommand(service, output(), clock))
                        .execute(
                            "--subject",
                            "alice",
                            "--session",
                            "mcp-1",
                            "--run",
                            "run-1",
                            "--tool",
                            "apply_patch",
                            "--workspace",
                            temporary.toString(),
                            "--action-digest",
                            digest,
                            "--ttl",
                            "PT1M")));
    assertTrue(issuedText.contains("\"token\""));
    var issued =
        service.issue(
            new CapabilityRequest(
                "alice",
                "mcp-1",
                "run-1",
                "apply_patch",
                temporary,
                digest,
                "",
                NOW.plusSeconds(60),
                1));
    var revoked =
        captureResult(
            () ->
                new CommandLine(new CapabilityRevokeCommand(service, output()))
                    .execute(issued.capability().id()));
    assertEquals(0, revoked.exitCode());
    assertTrue(revoked.text().contains("\"revoked\":true"));
    assertEquals(
        2,
        new CommandLine(new CapabilityRevokeCommand(service, output()))
            .execute("cap-000000000000000000000000"));
  }

  private static JsonOutput output() {
    return new JsonOutput(new ObjectMapper());
  }

  private static Project project() {
    return new Project(
        new ProjectId("project-1"),
        new RepositoryLocation("/repo"),
        "/repo",
        new GitRevision("0123456789012345678901234567890123456789"),
        NOW);
  }

  private static Task task() {
    return new Task(
        new TaskId("task-1"),
        new ProjectId("project-1"),
        new Requirement("work"),
        new AcceptanceCriteria(List.of("tests")),
        NOW);
  }

  private static String capture(Runnable operation) {
    var original = System.out;
    var bytes = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
      operation.run();
      return bytes.toString(StandardCharsets.UTF_8);
    } finally {
      System.setOut(original);
    }
  }

  private static Captured captureResult(java.util.function.IntSupplier operation) {
    var original = System.out;
    var bytes = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
      return new Captured(operation.getAsInt(), bytes.toString(StandardCharsets.UTF_8));
    } finally {
      System.setOut(original);
    }
  }

  private record Captured(int exitCode, String text) {}
}

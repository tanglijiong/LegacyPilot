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
import io.legacypilot.domain.task.TaskMode;
import io.legacypilot.eval.EvalAttemptCheckpoint;
import io.legacypilot.eval.EvalExperimentBudget;
import io.legacypilot.eval.EvalExperimentCheckpoint;
import io.legacypilot.eval.EvalExperimentManifest;
import io.legacypilot.eval.EvalExperimentStore;
import io.legacypilot.eval.EvalPricingSnapshot;
import io.legacypilot.eval.EvalTaskResult;
import io.legacypilot.model.ModelUsage;
import io.legacypilot.observability.InMemoryTraceSink;
import io.legacypilot.observability.ReportStore;
import io.legacypilot.observability.RunReport;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    when(createTask.create("project-1", TaskMode.ANALYZE, "work", List.of("tests")))
        .thenReturn(task);
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
                          "--mode",
                          "ANALYZE",
                          "--requirement",
                          "work",
                          "--criterion",
                          "tests"));
              assertEquals(
                  0, new CommandLine(new TaskShowCommand(getTask, output)).execute("task-1"));
            });
    assertTrue(text.contains("\"id\":{\"value\":\"project-1\"}"));
    assertTrue(text.contains("\"requirement\":{\"text\":\"work\"}"));
    verify(createTask).create("project-1", TaskMode.ANALYZE, "work", List.of("tests"));
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
  void rendersTaskResultAsJsonAndMarkdown() {
    var reports = mock(ReportStore.class);
    var report =
        new RunReport(
            "run-1",
            "SUCCEEDED",
            "source-grounded result verified",
            2,
            42,
            BigDecimal.ZERO,
            Duration.ofSeconds(1),
            "LOW",
            List.of("inspect transfer flow"),
            List.of(),
            List.of());
    when(reports.load("run-1")).thenReturn(Optional.of(report));
    var mapper = new ObjectMapper().findAndRegisterModules();

    var text =
        capture(
            () -> {
              assertEquals(
                  0,
                  new CommandLine(new TaskResultCommand(reports, new JsonOutput(mapper), mapper))
                      .execute("run-1"));
              assertEquals(
                  0,
                  new CommandLine(new TaskResultCommand(reports, new JsonOutput(mapper), mapper))
                      .execute("run-1", "--format", "MARKDOWN"));
            });

    assertTrue(text.contains("\"runId\":\"run-1\""));
    assertTrue(text.contains("# LegacyPilot Run run-1"));
    assertTrue(text.contains("Task mode: **CHANGE**"));
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
  void comparesCompletedExperimentsAndWritesOfflineEvidence() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var baseline = new EvalExperimentStore(temporary.resolve("baseline"), mapper);
    var candidate = new EvalExperimentStore(temporary.resolve("candidate"), mapper);
    createExperiment(baseline, "baseline", false);
    createExperiment(candidate, "candidate", true);
    var evidence = temporary.resolve("evidence");

    var result =
        captureResult(
            () ->
                new CommandLine(new EvalCompareCommand(mapper, new JsonOutput(mapper)))
                    .execute(
                        "--baseline",
                        baseline.root().toString(),
                        "--candidate",
                        candidate.root().toString(),
                        "--output",
                        evidence.toString()));

    assertEquals(0, result.exitCode());
    assertTrue(result.text().contains("\"passed\":true"));
    assertTrue(Files.exists(evidence.resolve("comparison.json")));
    assertTrue(Files.exists(evidence.resolve("report.md")));
    assertTrue(Files.exists(evidence.resolve("dashboard.html")));
  }

  @Test
  void startsAndResumesADurableProviderBackedEvalWithoutRepeatingCalls() throws Exception {
    var root = Path.of("../..").toAbsolutePath().normalize();
    var calls = temporary.resolve("codex-calls.txt");
    var codex = temporary.resolve("fake-codex");
    Files.writeString(
        codex,
        """
        #!/bin/sh
        if [ "$1" = "--version" ]; then echo 'codex-cli test'; exit 0; fi
        cat >/dev/null
        echo call >> '%s'
        printf '%%s\n' '{"type":"turn.completed","usage":{"input_tokens":10,"output_tokens":2}}'
        """
            .formatted(calls));
    assertTrue(codex.toFile().setExecutable(true));
    var maven = temporary.resolve("fake-mvnw");
    Files.writeString(maven, "#!/bin/sh\nexit 0\n");
    assertTrue(maven.toFile().setExecutable(true));
    var prompt = temporary.resolve("prompt.md");
    Files.writeString(prompt, "Implement this governed requirement: {{requirement}}\n");
    var experiment = temporary.resolve("run-model");
    var mapper = new ObjectMapper().findAndRegisterModules();
    var command =
        new EvalModelRunCommand(
            new JsonOutput(mapper),
            mapper,
            () -> "",
            () -> "0123456789abcdef0123456789abcdef01234567");

    var started =
        captureResult(
            () ->
                new CommandLine(command)
                    .execute(
                        "--dataset",
                        root.resolve("evals/datasets/v0.3").toString(),
                        "--output",
                        experiment.toString(),
                        "--run-id",
                        "test-provider-run",
                        "--model",
                        "fake-model",
                        "--prompt-file",
                        prompt.toString(),
                        "--prompt-version",
                        "prompt-v1",
                        "--input-price",
                        "1",
                        "--cached-input-price",
                        "0.1",
                        "--output-price",
                        "5",
                        "--pricing-source",
                        "test",
                        "--model-adapter",
                        "codex",
                        "--allow-external-provider",
                        "--codex-executable",
                        codex.toString(),
                        "--references",
                        root.resolve("evals/reference-solutions").toString(),
                        "--maven-wrapper",
                        maven.toString(),
                        "--concurrency",
                        "2"));
    assertEquals(0, started.exitCode());
    assertTrue(started.text().contains("COMPLETED"));
    assertEquals(20, Files.readAllLines(calls).size());

    var resumed =
        captureResult(
            () ->
                new CommandLine(
                        new EvalModelRunCommand(
                            new JsonOutput(mapper),
                            mapper,
                            () -> "dirty is ignored on resume",
                            () -> "0123456789abcdef0123456789abcdef01234567"))
                    .execute(
                        "--dataset",
                        root.resolve("evals/datasets/v0.3").toString(),
                        "--output",
                        experiment.toString(),
                        "--resume",
                        "--allow-external-provider",
                        "--codex-executable",
                        codex.toString(),
                        "--references",
                        root.resolve("evals/reference-solutions").toString(),
                        "--maven-wrapper",
                        maven.toString()));
    assertEquals(0, resumed.exitCode());
    assertEquals(20, Files.readAllLines(calls).size());
  }

  @Test
  void runsAndResumesTheDefaultAirGappedContainerAdapter() throws Exception {
    var root = Path.of("../..").toAbsolutePath().normalize();
    var calls = temporary.resolve("docker-calls.txt");
    var docker = temporary.resolve("fake-docker");
    Files.writeString(
        docker,
        """
        #!/bin/sh
        if [ "$1" = "--version" ]; then echo 'Docker test'; exit 0; fi
        printf '%%s\n' "$*" >> '%s'
        cat >/dev/null
        printf '%%s\n' '{"type":"turn.completed","usage":{"input_tokens":10,"output_tokens":2}}'
        """
            .formatted(calls));
    assertTrue(docker.toFile().setExecutable(true));
    var maven = temporary.resolve("offline-mvnw");
    Files.writeString(maven, "#!/bin/sh\nexit 0\n");
    assertTrue(maven.toFile().setExecutable(true));
    var prompt = temporary.resolve("airgap-prompt.md");
    Files.writeString(prompt, "Implement inside the bank boundary: {{requirement}}\n");
    var weights = temporary.resolve("deepseek-weights");
    Files.createDirectories(weights);
    var socket = temporary.resolve("deepseek-socket");
    Files.createDirectories(socket);
    Files.writeString(
        socket.resolve("service-manifest.json"),
        """
        {
          "image": "registry.bank.local/model-agent@sha256:%s",
          "model": "bank-code-model",
          "modelArtifactSha256": "%s",
          "memory": "24g",
          "cpus": 8,
          "pids": 1024,
          "gpus": "all",
          "tensorParallelSize": 1,
          "maxModelLength": 32768
        }
        """
            .formatted("a".repeat(64), "b".repeat(64)));
    var experiment = temporary.resolve("airgap-run");
    var mapper = new ObjectMapper().findAndRegisterModules();
    var commit = "0123456789abcdef0123456789abcdef01234567";

    var started =
        captureResult(
            () ->
                new CommandLine(
                        new EvalModelRunCommand(
                            new JsonOutput(mapper), mapper, () -> "", () -> commit))
                    .execute(
                        "--dataset",
                        root.resolve("evals/datasets/v0.3").toString(),
                        "--output",
                        experiment.toString(),
                        "--run-id",
                        "airgap-run",
                        "--agent-image",
                        "registry.bank.local/model-agent@sha256:" + "a".repeat(64),
                        "--model-weights",
                        weights.toString(),
                        "--model-socket-directory",
                        socket.toString(),
                        "--model-artifact-sha256",
                        "b".repeat(64),
                        "--agent-gpus",
                        "all",
                        "--task-ids",
                        "task-001,task-002,task-003,task-004,task-005",
                        "--model",
                        "bank-code-model",
                        "--prompt-file",
                        prompt.toString(),
                        "--prompt-version",
                        "bank-prompt-v1",
                        "--input-price",
                        "0",
                        "--cached-input-price",
                        "0",
                        "--output-price",
                        "0",
                        "--pricing-source",
                        "private-infrastructure",
                        "--docker-executable",
                        docker.toString(),
                        "--references",
                        root.resolve("evals/reference-solutions").toString(),
                        "--maven-wrapper",
                        maven.toString(),
                        "--concurrency",
                        "2"));

    assertEquals(0, started.exitCode());
    assertEquals(5, Files.readAllLines(calls).size());
    var manifest = mapper.readTree(experiment.resolve("manifest.json").toFile());
    assertEquals("airgap-container", manifest.path("environment").path("modelAdapter").asText());
    assertEquals("air-gapped", manifest.path("environment").path("networkBoundary").asText());
    assertEquals("all", manifest.path("environment").path("agentGpus").asText());
    assertEquals(5, manifest.path("taskIds").size());
    assertTrue(Files.readString(calls).contains("--pull never --network none"));

    var resumed =
        captureResult(
            () ->
                new CommandLine(
                        new EvalModelRunCommand(
                            new JsonOutput(mapper), mapper, () -> "dirty", () -> commit))
                    .execute(
                        "--dataset",
                        root.resolve("evals/datasets/v0.3").toString(),
                        "--output",
                        experiment.toString(),
                        "--resume",
                        "--docker-executable",
                        docker.toString(),
                        "--references",
                        root.resolve("evals/reference-solutions").toString(),
                        "--maven-wrapper",
                        maven.toString()));
    assertEquals(0, resumed.exitCode());
    assertEquals(5, Files.readAllLines(calls).size());
  }

  @Test
  void runsTheLocalHttpDeepSeekAdapterWithoutExternalConsent() throws Exception {
    var root = Path.of("../..").toAbsolutePath().normalize();
    var calls = temporary.resolve("local-http-calls.txt");
    var python = temporary.resolve("fake-python");
    Files.writeString(
        python,
        """
        #!/bin/sh
        if [ "$1" = "--version" ]; then echo 'Python test'; exit 0; fi
        cat >/dev/null
        printf '%%s\n' "$LEGACY_PILOT_MODEL_BASE_URL" >> '%s'
        printf '%%s\n' '{"type":"turn.completed","usage":{"input_tokens":10,"output_tokens":2}}'
        """
            .formatted(calls));
    assertTrue(python.toFile().setExecutable(true));
    var maven = temporary.resolve("local-mvnw");
    Files.writeString(maven, "#!/bin/sh\nexit 0\n");
    assertTrue(maven.toFile().setExecutable(true));
    var prompt = temporary.resolve("local-prompt.md");
    Files.writeString(prompt, "Implement with local DeepSeek: {{requirement}}\n");
    var experiment = temporary.resolve("local-http-run");
    var mapper = new ObjectMapper().findAndRegisterModules();
    var command =
        new EvalModelRunCommand(
            new JsonOutput(mapper),
            mapper,
            () -> "",
            () -> "0123456789abcdef0123456789abcdef01234567");

    var result =
        captureResult(
            () ->
                new CommandLine(command)
                    .execute(
                        "--dataset",
                        root.resolve("evals/datasets/v0.3").toString(),
                        "--output",
                        experiment.toString(),
                        "--run-id",
                        "local-http-run",
                        "--model-adapter",
                        "local-http",
                        "--model-base-url",
                        "http://127.0.0.1:12434/engines/v1",
                        "--python-executable",
                        python.toString(),
                        "--local-agent-script",
                        root.resolve("deploy/deepseek/model_agent.py").toString(),
                        "--model",
                        "deepseek-local",
                        "--prompt-file",
                        prompt.toString(),
                        "--prompt-version",
                        "local-prompt-v1",
                        "--input-price",
                        "0",
                        "--cached-input-price",
                        "0",
                        "--output-price",
                        "0",
                        "--pricing-source",
                        "local-compute",
                        "--task-ids",
                        "task-001",
                        "--references",
                        root.resolve("evals/reference-solutions").toString(),
                        "--maven-wrapper",
                        maven.toString()));

    assertEquals(0, result.exitCode());
    assertEquals("http://127.0.0.1:12434/engines/v1", Files.readString(calls).strip());
    var manifest = mapper.readTree(experiment.resolve("manifest.json").toFile());
    assertEquals("local-http", manifest.path("environment").path("modelAdapter").asText());
    assertEquals("local-only", manifest.path("environment").path("networkBoundary").asText());
  }

  @Test
  void rejectsIncompleteDirtyAndInvalidProviderEvalStarts() throws Exception {
    var root = Path.of("../..").toAbsolutePath().normalize();
    var mapper = new ObjectMapper().findAndRegisterModules();
    var incomplete =
        captureResult(
            () ->
                new CommandLine(new EvalModelRunCommand(new JsonOutput(mapper), mapper))
                    .execute(
                        "--dataset",
                        root.resolve("evals/datasets/v0.3").toString(),
                        "--output",
                        temporary.resolve("incomplete").toString()));
    assertEquals(1, incomplete.exitCode());

    var validPrompt = temporary.resolve("valid-prompt.md");
    Files.writeString(validPrompt, "Implement {{requirement}}");
    var dirty =
        captureResult(
            () ->
                new CommandLine(
                        new EvalModelRunCommand(
                            new JsonOutput(mapper),
                            mapper,
                            () -> " M source.java",
                            () -> "0123456789abcdef0123456789abcdef01234567"))
                    .execute(
                        providerStartArguments(
                            root,
                            temporary.resolve("dirty"),
                            validPrompt,
                            temporary.resolve("unused"))));
    assertEquals(1, dirty.exitCode());

    var invalidPrompt = temporary.resolve("invalid-prompt.md");
    Files.writeString(invalidPrompt, "missing placeholder");
    var invalid =
        captureResult(
            () ->
                new CommandLine(
                        new EvalModelRunCommand(
                            new JsonOutput(mapper),
                            mapper,
                            () -> "",
                            () -> "0123456789abcdef0123456789abcdef01234567"))
                    .execute(
                        providerStartArguments(
                            root,
                            temporary.resolve("invalid"),
                            invalidPrompt,
                            temporary.resolve("unused"))));
    assertEquals(1, invalid.exitCode());

    var externalArguments =
        new java.util.ArrayList<>(
            List.of(
                providerStartArguments(
                    root,
                    temporary.resolve("external"),
                    validPrompt,
                    temporary.resolve("unused"))));
    externalArguments.remove("--allow-external-provider");
    var externalWithoutConsent =
        captureResult(
            () ->
                new CommandLine(
                        new EvalModelRunCommand(
                            new JsonOutput(mapper),
                            mapper,
                            () -> "",
                            () -> "0123456789abcdef0123456789abcdef01234567"))
                    .execute(externalArguments.toArray(String[]::new)));
    assertEquals(1, externalWithoutConsent.exitCode());
    assertTrue(externalWithoutConsent.text().contains("explicit governed adapter"));
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

  private static void createExperiment(
      EvalExperimentStore store, String runId, boolean secondTaskPassed) {
    store.createManifest(
        new EvalExperimentManifest(
            "eval-experiment-v1",
            runId,
            "abcdef1",
            "dataset-v1",
            "a".repeat(64),
            "model",
            "high",
            "prompt-v1",
            "b".repeat(64),
            "policy-v1",
            new EvalPricingSnapshot(
                "USD", "per-1m-tokens", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "test"),
            Map.of(),
            new EvalExperimentBudget(BigDecimal.TEN, Duration.ofHours(1), 1000, 1, 1),
            List.of("task-001", "task-002"),
            NOW));
    var attempts = new LinkedHashMap<String, EvalAttemptCheckpoint>();
    attempts.put("task-001", completedAttempt(runId, "task-001", true));
    attempts.put("task-002", completedAttempt(runId, "task-002", secondTaskPassed));
    store.save(
        new EvalExperimentCheckpoint(
            runId, EvalExperimentCheckpoint.Status.COMPLETED, attempts, NOW));
  }

  private static EvalAttemptCheckpoint completedAttempt(
      String runId, String taskId, boolean passed) {
    var result =
        new EvalTaskResult(
            taskId,
            passed ? EvalTaskResult.Status.PASSED : EvalTaskResult.Status.FAILED,
            passed ? 1 : 0,
            1,
            true,
            passed,
            1,
            1,
            10,
            BigDecimal.ZERO,
            Duration.ofSeconds(1),
            List.of(),
            passed ? "" : "tests failed");
    return EvalAttemptCheckpoint.pending(runId, taskId).running(NOW).completed(result, NOW);
  }

  private static String[] providerStartArguments(
      Path root, Path output, Path prompt, Path executable) {
    return new String[] {
      "--dataset",
      root.resolve("evals/datasets/v0.3").toString(),
      "--output",
      output.toString(),
      "--run-id",
      "invalid-run",
      "--model",
      "fake-model",
      "--prompt-file",
      prompt.toString(),
      "--prompt-version",
      "prompt-v1",
      "--input-price",
      "1",
      "--cached-input-price",
      "0.1",
      "--output-price",
      "5",
      "--pricing-source",
      "test",
      "--model-adapter",
      "codex",
      "--allow-external-provider",
      "--codex-executable",
      executable.toString()
    };
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
    var originalError = System.err;
    var bytes = new ByteArrayOutputStream();
    try (var captured = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      System.setOut(captured);
      System.setErr(captured);
      return new Captured(operation.getAsInt(), bytes.toString(StandardCharsets.UTF_8));
    } finally {
      System.setOut(original);
      System.setErr(originalError);
    }
  }

  private record Captured(int exitCode, String text) {}
}

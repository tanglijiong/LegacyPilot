package io.legacypilot.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.eval.CodexEvalTaskExecutor;
import io.legacypilot.eval.EvalDatasetLoader;
import io.legacypilot.eval.EvalExperimentBudget;
import io.legacypilot.eval.EvalExperimentManifest;
import io.legacypilot.eval.EvalExperimentStore;
import io.legacypilot.eval.EvalPricingSnapshot;
import io.legacypilot.eval.MavenFixtureVerifier;
import io.legacypilot.eval.ResumableEvalRunner;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Component
@Command(
    name = "eval-model-run",
    mixinStandardHelpOptions = true,
    description = "Run or resume a durable provider-backed evaluation experiment.")
public class EvalModelRunCommand implements Callable<Integer> {
  private final JsonOutput output;
  private final ObjectMapper mapper;
  private final Supplier<String> repositoryStatus;
  private final Supplier<String> repositoryCommit;

  @Option(names = "--dataset", defaultValue = "evals/datasets/v0.3")
  private Path dataset;

  @Option(names = "--output", required = true)
  private Path experimentRoot;

  @Option(names = "--resume")
  private boolean resume;

  @Option(names = "--run-id")
  private String runId;

  @Option(names = "--model")
  private String model;

  @Option(names = "--reasoning-effort", defaultValue = "high")
  private String reasoningEffort;

  @Option(names = "--prompt-file")
  private Path promptFile;

  @Option(names = "--prompt-version")
  private String promptVersion;

  @Option(names = "--policy-version", defaultValue = "codex-agent-v1")
  private String policyVersion;

  @Option(names = "--input-price")
  private BigDecimal inputPrice;

  @Option(names = "--cached-input-price")
  private BigDecimal cachedInputPrice;

  @Option(names = "--output-price")
  private BigDecimal outputPrice;

  @Option(names = "--pricing-source")
  private String pricingSource;

  @Option(names = "--maximum-cost-usd", defaultValue = "10.00")
  private BigDecimal maximumCostUsd;

  @Option(names = "--maximum-duration", defaultValue = "PT4H")
  private Duration maximumDuration;

  @Option(names = "--maximum-provider-errors", defaultValue = "3")
  private int maximumProviderErrors;

  @Option(names = "--maximum-tokens", defaultValue = "2000000")
  private int maximumTokens;

  @Option(names = "--concurrency", defaultValue = "1")
  private int concurrency;

  @Option(names = "--codex-executable", defaultValue = "codex")
  private String codexExecutable;

  @Option(names = "--references", defaultValue = "evals/reference-solutions")
  private Path references;

  @Option(names = "--maven-wrapper", defaultValue = "mvnw")
  private Path mavenWrapper;

  @Autowired
  public EvalModelRunCommand(JsonOutput output, ObjectMapper mapper) {
    this(
        output,
        mapper,
        () -> commandOutput(Path.of("git"), "status", "--porcelain"),
        () -> commandOutput(Path.of("git"), "rev-parse", "HEAD"));
  }

  EvalModelRunCommand(
      JsonOutput output,
      ObjectMapper mapper,
      Supplier<String> repositoryStatus,
      Supplier<String> repositoryCommit) {
    this.output = output;
    this.mapper = mapper;
    this.repositoryStatus = repositoryStatus;
    this.repositoryCommit = repositoryCommit;
  }

  @Override
  public Integer call() {
    var loaded = new EvalDatasetLoader().loadVersioned(dataset);
    var store = new EvalExperimentStore(experimentRoot, mapper);
    EvalExperimentManifest manifest;
    String prompt;
    if (resume) {
      manifest = store.manifest();
      prompt = readPrompt(experimentRoot.resolve("prompt.txt"));
      if (!sha256(prompt).equals(manifest.promptSha256())) {
        throw new IllegalStateException("persisted eval prompt does not match its manifest");
      }
      if (!manifest.datasetVersion().equals(loaded.datasetVersion())
          || !manifest.datasetSha256().equals(loaded.datasetSha256())) {
        throw new IllegalStateException("eval dataset does not match the persisted manifest");
      }
      if (!manifest.repositoryCommit().equals(repositoryCommit())) {
        throw new IllegalStateException("repository commit does not match the persisted manifest");
      }
    } else {
      requireStartOptions();
      requireCleanRepository();
      prompt = readPrompt(promptFile);
      persistPrompt(prompt);
      manifest = createManifest(loaded, prompt);
    }
    var executable = resolveExecutable(codexExecutable);
    var verifier =
        new MavenFixtureVerifier(mavenWrapper.toAbsolutePath().normalize(), Duration.ofMinutes(3));
    var executor =
        new CodexEvalTaskExecutor(
            loaded.fixtures().entrySet().stream()
                .collect(
                    java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue().path())),
            experimentRoot.resolve("workspaces"),
            references,
            executable,
            manifest.model(),
            manifest.reasoningEffort(),
            prompt,
            manifest.pricing(),
            verifier,
            mapper);
    var runner = new ResumableEvalRunner(Clock.systemUTC(), store);
    var checkpoint =
        resume
            ? runner.resume(loaded.tasks(), executor)
            : runner.start(manifest, loaded.tasks(), executor);
    output.write(checkpoint);
    return checkpoint.status() == io.legacypilot.eval.EvalExperimentCheckpoint.Status.COMPLETED
        ? 0
        : 2;
  }

  private EvalExperimentManifest createManifest(
      io.legacypilot.eval.EvalDataset loaded, String prompt) {
    return new EvalExperimentManifest(
        "eval-experiment-v1",
        runId,
        repositoryCommit(),
        loaded.datasetVersion(),
        loaded.datasetSha256(),
        model,
        reasoningEffort,
        promptVersion,
        sha256(prompt),
        policyVersion,
        new EvalPricingSnapshot(
            "USD", "per-1m-tokens", inputPrice, cachedInputPrice, outputPrice, pricingSource),
        Map.of(
            "java", System.getProperty("java.version"),
            "os", System.getProperty("os.name"),
            "architecture", System.getProperty("os.arch"),
            "codex", commandOutput(resolveExecutable(codexExecutable), "--version")),
        new EvalExperimentBudget(
            maximumCostUsd, maximumDuration, maximumTokens, maximumProviderErrors, concurrency),
        loaded.tasks().stream().map(io.legacypilot.eval.EvalTask::id).toList(),
        Instant.now());
  }

  private void requireStartOptions() {
    if (runId == null
        || runId.isBlank()
        || model == null
        || model.isBlank()
        || promptFile == null
        || promptVersion == null
        || promptVersion.isBlank()
        || inputPrice == null
        || cachedInputPrice == null
        || outputPrice == null
        || pricingSource == null
        || pricingSource.isBlank()) {
      throw new IllegalArgumentException(
          "new eval runs require run id, model, prompt metadata, and a complete price snapshot");
    }
  }

  private void persistPrompt(String prompt) {
    try {
      Files.createDirectories(experimentRoot);
      Files.writeString(
          experimentRoot.resolve("prompt.txt"),
          prompt,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE);
    } catch (FileAlreadyExistsException exception) {
      throw new IllegalStateException("eval experiment prompt already exists", exception);
    } catch (IOException exception) {
      throw new IllegalStateException("unable to persist eval experiment prompt", exception);
    }
  }

  private static String readPrompt(Path path) {
    try {
      var prompt = Files.readString(path, StandardCharsets.UTF_8);
      if (prompt.isBlank() || !prompt.contains("{{requirement}}")) {
        throw new IllegalArgumentException("eval prompt must contain {{requirement}}");
      }
      if (java.util.regex.Pattern.compile(
              "(?i)(api[_-]?key|password|secret|token|authorization)\\s*[:=]\\s*\\S+|sk-[A-Za-z0-9_-]{16,}")
          .matcher(prompt)
          .find()) {
        throw new IllegalArgumentException("eval prompt appears to contain a credential");
      }
      return prompt;
    } catch (IOException exception) {
      throw new IllegalArgumentException("eval prompt is unavailable", exception);
    }
  }

  private void requireCleanRepository() {
    if (!repositoryStatus.get().isBlank()) {
      throw new IllegalStateException("provider-backed eval requires a clean repository");
    }
  }

  private String repositoryCommit() {
    var value = repositoryCommit.get();
    if (!value.matches("[0-9a-f]{40}")) {
      throw new IllegalStateException("repository commit is unavailable");
    }
    return value;
  }

  private static String commandOutput(Path executable, String... arguments) {
    var command = new java.util.ArrayList<String>();
    command.add(executable.toString());
    command.addAll(java.util.List.of(arguments));
    try {
      var process = new ProcessBuilder(command).redirectErrorStream(true).start();
      var text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
        process.destroyForcibly();
        throw new IllegalStateException("unable to inspect eval execution environment");
      }
      return text;
    } catch (IOException exception) {
      throw new IllegalStateException("unable to inspect eval execution environment", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("eval environment inspection was interrupted", exception);
    }
  }

  private static Path resolveExecutable(String value) {
    var direct = Path.of(value);
    if (direct.getNameCount() > 1 || direct.isAbsolute()) {
      var normalized = direct.toAbsolutePath().normalize();
      if (Files.isRegularFile(normalized) && Files.isExecutable(normalized)) {
        return normalized;
      }
      throw new IllegalArgumentException("eval executable is unavailable");
    }
    var path = System.getenv().getOrDefault("PATH", "");
    for (var directory : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
      if (directory.isBlank()) {
        continue;
      }
      var candidate = Path.of(directory).resolve(value).toAbsolutePath().normalize();
      if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
        return candidate;
      }
    }
    throw new IllegalArgumentException("eval executable is unavailable");
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}

package io.legacypilot.cli;

import io.legacypilot.eval.EvalDatasetLoader;
import io.legacypilot.eval.EvalRunner;
import io.legacypilot.eval.MavenFixtureVerifier;
import io.legacypilot.eval.ReferenceBaselineExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Component
@Command(name = "eval-run", description = "Run a versioned deterministic evaluation dataset.")
public class EvalRunCommand implements Callable<Integer> {
  private final JsonOutput output;

  @Option(names = "--dataset", defaultValue = "evals/datasets/v0.3")
  private Path dataset;

  @Option(names = "--fixture", defaultValue = "samples/banking-demo")
  private Path fixture;

  @Option(names = "--references", defaultValue = "evals/reference-solutions")
  private Path references;

  @Option(names = "--maven-wrapper", defaultValue = "mvnw")
  private Path mavenWrapper;

  @Option(names = "--concurrency", defaultValue = "2")
  private int concurrency;

  public EvalRunCommand(JsonOutput output) {
    this.output = output;
  }

  @Override
  public Integer call() {
    var loader = new EvalDatasetLoader();
    var verifier = new MavenFixtureVerifier(mavenWrapper, Duration.ofMinutes(3));
    var versioned = Files.isRegularFile(dataset.resolve("manifest.yml"));
    var loaded = versioned ? loader.loadVersioned(dataset) : null;
    var tasks = versioned ? loaded.tasks() : loader.load(dataset);
    var executor =
        versioned
            ? new ReferenceBaselineExecutor(
                loaded.fixtures().entrySet().stream()
                    .collect(
                        java.util.stream.Collectors.toMap(
                            Map.Entry::getKey, entry -> entry.getValue().path())),
                references,
                verifier)
            : new ReferenceBaselineExecutor(fixture, references, verifier);
    var summary =
        new EvalRunner(Clock.systemUTC())
            .run(
                versioned ? loaded.datasetVersion() : "v0.1",
                "reference-ceiling",
                "planner-v1",
                "policy-v1",
                Map.of(
                    "java", System.getProperty("java.version"),
                    "os", System.getProperty("os.name")),
                tasks,
                concurrency,
                executor);
    output.write(summary);
    return summary.successRate() >= 0.8 ? 0 : 2;
  }
}

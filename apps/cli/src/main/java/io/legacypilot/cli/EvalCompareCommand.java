package io.legacypilot.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.eval.EvalComparisonRenderer;
import io.legacypilot.eval.EvalComparisonService;
import io.legacypilot.eval.EvalExperimentStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Component
@Command(
    name = "eval-compare",
    mixinStandardHelpOptions = true,
    description = "Compare two completed eval experiments and render offline evidence.")
public final class EvalCompareCommand implements Callable<Integer> {
  private final ObjectMapper mapper;
  private final JsonOutput output;

  @Option(names = "--baseline", required = true)
  private Path baselineRoot;

  @Option(names = "--candidate", required = true)
  private Path candidateRoot;

  @Option(names = "--output", required = true)
  private Path outputRoot;

  public EvalCompareCommand(ObjectMapper mapper, JsonOutput output) {
    this.mapper = mapper;
    this.output = output;
  }

  @Override
  public Integer call() {
    var baseline = new EvalExperimentStore(baselineRoot, mapper);
    var candidate = new EvalExperimentStore(candidateRoot, mapper);
    var comparison =
        new EvalComparisonService(Clock.systemUTC())
            .compare(
                baseline.manifest(),
                baseline
                    .load()
                    .orElseThrow(() -> new IllegalStateException("baseline checkpoint is missing")),
                candidate.manifest(),
                candidate
                    .load()
                    .orElseThrow(
                        () -> new IllegalStateException("candidate checkpoint is missing")));
    var renderer = new EvalComparisonRenderer(mapper);
    try {
      Files.createDirectory(outputRoot);
      Files.writeString(
          outputRoot.resolve("comparison.json"), renderer.json(comparison), StandardCharsets.UTF_8);
      Files.writeString(
          outputRoot.resolve("report.md"), renderer.markdown(comparison), StandardCharsets.UTF_8);
      Files.writeString(
          outputRoot.resolve("dashboard.html"), renderer.html(comparison), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("unable to write eval comparison artifacts", exception);
    }
    output.write(comparison);
    return comparison.gate().passed() ? 0 : 2;
  }
}

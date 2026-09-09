package io.legacypilot.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.observability.ReportRenderer;
import io.legacypilot.observability.ReportStore;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "task-result", description = "Show a completed task report or artifact.")
public final class TaskResultCommand implements Callable<Integer> {

  enum Format {
    JSON,
    MARKDOWN
  }

  private final ReportStore reports;
  private final JsonOutput output;
  private final ReportRenderer renderer;

  @Parameters(index = "0", paramLabel = "RUN_ID")
  private String runId;

  @Option(names = "--format", defaultValue = "JSON")
  private Format format;

  public TaskResultCommand(ReportStore reports, JsonOutput output, ObjectMapper mapper) {
    this.reports = reports;
    this.output = output;
    this.renderer = new ReportRenderer(mapper);
  }

  @Override
  public Integer call() {
    var report =
        reports
            .load(runId)
            .orElseThrow(() -> new IllegalArgumentException("task result was not found: " + runId));
    if (format == Format.MARKDOWN) {
      System.out.print(renderer.markdown(report));
    } else {
      output.write(report);
    }
    return 0;
  }
}

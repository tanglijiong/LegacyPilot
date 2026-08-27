package io.legacypilot.cli;

import io.legacypilot.application.service.GetRunStatusUseCase;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "run-status", description = "Show durable task-run status.")
public class RunStatusCommand implements Callable<Integer> {

  private final GetRunStatusUseCase useCase;
  private final JsonOutput output;

  @Parameters(index = "0", paramLabel = "RUN_ID")
  private String runId;

  public RunStatusCommand(GetRunStatusUseCase useCase, JsonOutput output) {
    this.useCase = useCase;
    this.output = output;
  }

  @Override
  public Integer call() {
    output.write(useCase.get(runId));
    return 0;
  }
}

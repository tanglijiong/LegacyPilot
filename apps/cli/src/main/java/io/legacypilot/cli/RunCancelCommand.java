package io.legacypilot.cli;

import io.legacypilot.application.service.CancelRunUseCase;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "run-cancel", description = "Cancel a run and remove its managed worktree.")
public class RunCancelCommand implements Callable<Integer> {

  private final CancelRunUseCase useCase;
  private final JsonOutput output;

  @Parameters(index = "0", paramLabel = "RUN_ID")
  private String runId;

  public RunCancelCommand(CancelRunUseCase useCase, JsonOutput output) {
    this.useCase = useCase;
    this.output = output;
  }

  @Override
  public Integer call() {
    output.write(useCase.cancel(runId));
    return 0;
  }
}

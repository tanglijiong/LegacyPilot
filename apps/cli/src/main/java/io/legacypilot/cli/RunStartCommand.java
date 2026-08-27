package io.legacypilot.cli;

import io.legacypilot.application.service.StartRunUseCase;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "run-start", description = "Start a task run and prepare its isolated worktree.")
public class RunStartCommand implements Callable<Integer> {

  private final StartRunUseCase useCase;
  private final JsonOutput output;

  @Parameters(index = "0", paramLabel = "TASK_ID")
  private String taskId;

  public RunStartCommand(StartRunUseCase useCase, JsonOutput output) {
    this.useCase = useCase;
    this.output = output;
  }

  @Override
  public Integer call() {
    output.write(useCase.start(taskId));
    return 0;
  }
}

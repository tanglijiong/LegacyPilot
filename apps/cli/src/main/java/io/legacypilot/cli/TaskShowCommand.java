package io.legacypilot.cli;

import io.legacypilot.application.service.GetTaskUseCase;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "task-show", description = "Show a task.")
public class TaskShowCommand implements Callable<Integer> {

  private final GetTaskUseCase useCase;
  private final JsonOutput output;

  @Parameters(index = "0", paramLabel = "TASK_ID")
  private String taskId;

  public TaskShowCommand(GetTaskUseCase useCase, JsonOutput output) {
    this.useCase = useCase;
    this.output = output;
  }

  @Override
  public Integer call() {
    output.write(useCase.get(taskId));
    return 0;
  }
}

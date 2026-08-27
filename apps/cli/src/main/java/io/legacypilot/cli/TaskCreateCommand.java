package io.legacypilot.cli;

import io.legacypilot.application.service.CreateTaskUseCase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Component
@Command(name = "task-create", description = "Create a task for a registered project.")
public class TaskCreateCommand implements Callable<Integer> {

  private final CreateTaskUseCase useCase;
  private final JsonOutput output;

  @Option(names = "--project", required = true)
  private String projectId;

  @Option(names = "--requirement", required = true)
  private String requirement;

  @Option(names = "--criterion")
  private List<String> criteria = new ArrayList<>();

  public TaskCreateCommand(CreateTaskUseCase useCase, JsonOutput output) {
    this.useCase = useCase;
    this.output = output;
  }

  @Override
  public Integer call() {
    output.write(useCase.create(projectId, requirement, criteria));
    return 0;
  }
}

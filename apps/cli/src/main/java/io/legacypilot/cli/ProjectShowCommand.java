package io.legacypilot.cli;

import io.legacypilot.application.service.GetProjectUseCase;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "project-show", description = "Show a registered project.")
public class ProjectShowCommand implements Callable<Integer> {

  private final GetProjectUseCase useCase;
  private final JsonOutput output;

  @Parameters(index = "0", paramLabel = "PROJECT_ID")
  private String projectId;

  public ProjectShowCommand(GetProjectUseCase useCase, JsonOutput output) {
    this.useCase = useCase;
    this.output = output;
  }

  @Override
  public Integer call() {
    output.write(useCase.get(projectId));
    return 0;
  }
}

package io.legacypilot.cli;

import io.legacypilot.application.service.RegisterProjectUseCase;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Component
@Command(
    name = "project-register",
    description = "Register a clean local or public Git repository.")
public class ProjectRegisterCommand implements Callable<Integer> {

  private final RegisterProjectUseCase useCase;
  private final JsonOutput output;

  @Option(names = "--source", required = true)
  private String source;

  @Option(names = "--revision")
  private String revision;

  public ProjectRegisterCommand(RegisterProjectUseCase useCase, JsonOutput output) {
    this.useCase = useCase;
    this.output = output;
  }

  @Override
  public Integer call() {
    output.write(useCase.register(source, revision));
    return 0;
  }
}

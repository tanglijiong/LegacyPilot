package io.legacypilot.cli;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

@Component
public class CliExecution implements ApplicationRunner {

  private final LegacyPilotCommand command;
  private final IFactory factory;
  private int exitCode;

  public CliExecution(LegacyPilotCommand command, IFactory factory) {
    this.command = command;
    this.factory = factory;
  }

  @Override
  public void run(ApplicationArguments arguments) {
    exitCode = new CommandLine(command, factory).execute(arguments.getSourceArgs());
  }

  int exitCode() {
    return exitCode;
  }
}

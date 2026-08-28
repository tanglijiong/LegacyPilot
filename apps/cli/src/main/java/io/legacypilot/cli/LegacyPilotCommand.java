package io.legacypilot.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

@Component
@Command(
    name = "legacy-pilot",
    description = "Safely orchestrate changes to Java legacy repositories.",
    mixinStandardHelpOptions = true,
    subcommands = {
      ProjectRegisterCommand.class,
      ProjectShowCommand.class,
      TaskCreateCommand.class,
      TaskShowCommand.class,
      RunStartCommand.class,
      RunStatusCommand.class,
      RunCancelCommand.class,
      AgentApproveCommand.class,
      AgentResumeCommand.class,
      AgentRecoverCommand.class,
      AgentStateCheckCommand.class,
      CapabilityIssueCommand.class,
      CapabilityRevokeCommand.class,
      EvalRunCommand.class
    })
public class LegacyPilotCommand implements Runnable {

  @Override
  public void run() {
    throw new picocli.CommandLine.ParameterException(
        new picocli.CommandLine(this), "Specify a command or use --help");
  }
}

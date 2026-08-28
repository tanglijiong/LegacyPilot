package io.legacypilot.cli;

import io.legacypilot.runtime.RecoveryCoordinator;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

@Component
@Command(name = "agent-recover", description = "Safely inspect and resume recoverable agent runs.")
public final class AgentRecoverCommand implements Callable<Integer> {
  private final RecoveryCoordinator recovery;
  private final JsonOutput output;

  public AgentRecoverCommand(RecoveryCoordinator recovery, JsonOutput output) {
    this.recovery = recovery;
    this.output = output;
  }

  @Override
  public Integer call() {
    var outcomes = recovery.recoverAll();
    output.write(outcomes);
    return outcomes.stream()
            .anyMatch(
                value -> value.decision() == io.legacypilot.runtime.RecoveryOutcome.Decision.FAILED)
        ? 2
        : 0;
  }
}

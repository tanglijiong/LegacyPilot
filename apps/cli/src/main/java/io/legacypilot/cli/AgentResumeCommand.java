package io.legacypilot.cli;

import io.legacypilot.runtime.AgentRuntime;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "agent-resume", description = "Resume a persisted agent run after review.")
public class AgentResumeCommand implements Callable<Integer> {
  private final AgentRuntime runtime;
  private final JsonOutput output;

  @Parameters(index = "0", paramLabel = "RUN_ID")
  private String runId;

  public AgentResumeCommand(AgentRuntime runtime, JsonOutput output) {
    this.runtime = runtime;
    this.output = output;
  }

  @Override
  public Integer call() {
    output.write(runtime.resume(runId));
    return 0;
  }
}

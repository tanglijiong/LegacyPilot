package io.legacypilot.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.runtime.FileAgentRunRequestStore;
import io.legacypilot.runtime.FileCheckpointStore;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "agent-state-check", description = "Inspect persisted agent state compatibility.")
public final class AgentStateCheckCommand implements Callable<Integer> {
  private final ObjectMapper mapper;

  @Parameters(index = "0", paramLabel = "RUN_ID")
  private String runId;

  @Option(
      names = "--state-root",
      defaultValue = ".legacy-pilot/agent",
      description = "Agent state root.")
  private Path stateRoot;

  public AgentStateCheckCommand(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Integer call() throws Exception {
    var checkpoint =
        new FileCheckpointStore(stateRoot.resolve("checkpoints"), mapper).inspect(runId);
    var request =
        new FileAgentRunRequestStore(stateRoot.resolve("requests"), mapper).inspect(runId);
    System.out.println(
        mapper.writeValueAsString(
            Map.of("runId", runId, "checkpoint", checkpoint, "request", request)));
    var unsafe =
        java.util.Set.of(
            io.legacypilot.state.StateHealth.CORRUPT, io.legacypilot.state.StateHealth.UNSUPPORTED);
    return unsafe.contains(checkpoint.health()) || unsafe.contains(request.health()) ? 2 : 0;
  }
}

package io.legacypilot.cli;

import io.legacypilot.runtime.ApprovalScope;
import io.legacypilot.runtime.ApprovalStore;
import io.legacypilot.runtime.RuntimeApproval;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "agent-approve", description = "Approve or deny one paused agent action.")
public class AgentApproveCommand implements Callable<Integer> {
  private final ApprovalStore approvals;
  private final JsonOutput output;

  @Parameters(index = "0", paramLabel = "RUN_ID")
  private String runId;

  @Option(names = "--action-digest", required = true)
  private String actionDigest;

  @Option(names = "--plan-digest", required = true)
  private String planDigest;

  @Option(names = "--actor", required = true)
  private String actor;

  @Option(names = "--decision", defaultValue = "APPROVED")
  private RuntimeApproval.Decision decision;

  @Option(names = "--scope", defaultValue = "ONCE")
  private ApprovalScope scope;

  @Option(names = "--reason", defaultValue = "")
  private String reason;

  @Option(names = "--valid-minutes", defaultValue = "30")
  private long validMinutes;

  public AgentApproveCommand(ApprovalStore approvals, JsonOutput output) {
    this.approvals = approvals;
    this.output = output;
  }

  @Override
  public Integer call() {
    var approval =
        new RuntimeApproval(
            runId,
            actionDigest,
            planDigest,
            actor,
            decision,
            scope,
            reason,
            Instant.now().plusSeconds(Math.multiplyExact(validMinutes, 60)));
    approvals.save(approval);
    output.write(Map.of("runId", runId, "decision", decision.name(), "scope", scope.name()));
    return 0;
  }
}

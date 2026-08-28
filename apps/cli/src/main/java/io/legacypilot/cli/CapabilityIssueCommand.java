package io.legacypilot.cli;

import io.legacypilot.runtime.CapabilityRequest;
import io.legacypilot.runtime.CapabilityService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Component
@Command(name = "capability-issue", description = "Issue a scoped, short-lived tool capability.")
public final class CapabilityIssueCommand implements Callable<Integer> {
  private final CapabilityService capabilities;
  private final JsonOutput output;
  private final Clock clock;

  @Option(names = "--subject", required = true)
  String subject;

  @Option(names = "--session", required = true)
  String sessionId;

  @Option(names = "--run", required = true)
  String runId;

  @Option(names = "--tool", required = true)
  String tool;

  @Option(names = "--workspace", required = true)
  Path workspace;

  @Option(names = "--action-digest", required = true)
  String actionDigest;

  @Option(names = "--plan-digest", defaultValue = "")
  String planDigest;

  @Option(names = "--ttl", defaultValue = "PT10M")
  Duration ttl;

  @Option(names = "--maximum-uses", defaultValue = "1")
  int maximumUses;

  public CapabilityIssueCommand(CapabilityService capabilities, JsonOutput output, Clock clock) {
    this.capabilities = capabilities;
    this.output = output;
    this.clock = clock;
  }

  @Override
  public Integer call() {
    if (ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("capability TTL must be positive");
    }
    output.write(
        capabilities.issue(
            new CapabilityRequest(
                subject,
                sessionId,
                runId,
                tool,
                workspace,
                actionDigest,
                planDigest,
                clock.instant().plus(ttl),
                maximumUses)));
    return 0;
  }
}

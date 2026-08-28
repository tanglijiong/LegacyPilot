package io.legacypilot.cli;

import io.legacypilot.runtime.CapabilityService;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Component
@Command(name = "capability-revoke", description = "Revoke a previously issued capability.")
public final class CapabilityRevokeCommand implements Callable<Integer> {
  private final CapabilityService capabilities;
  private final JsonOutput output;

  @Parameters(index = "0", paramLabel = "CAPABILITY_ID")
  String id;

  public CapabilityRevokeCommand(CapabilityService capabilities, JsonOutput output) {
    this.capabilities = capabilities;
    this.output = output;
  }

  @Override
  public Integer call() {
    var revoked = capabilities.revoke(id);
    if (revoked.isEmpty()) {
      return 2;
    }
    output.write(revoked.orElseThrow());
    return 0;
  }
}

package io.legacypilot.verification;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;

public final class WorkspaceIntegrityCheck implements VerificationCheck {

  @Override
  public VerificationEvidence verify(VerificationContext context) {
    var started = Instant.now();
    try {
      var root = context.workspace().toRealPath();
      try (var paths = Files.walk(root)) {
        var escape =
            paths
                .filter(Files::isSymbolicLink)
                .map(path -> path.toAbsolutePath().normalize())
                .filter(
                    path -> {
                      try {
                        return !path.toRealPath().startsWith(root);
                      } catch (IOException exception) {
                        return true;
                      }
                    })
                .findFirst();
        if (escape.isPresent()) {
          return evidence(
              VerificationStatus.BLOCKED,
              "Symbolic link escapes workspace: " + root.relativize(escape.get()),
              started);
        }
      }
      return evidence(VerificationStatus.PASSED, "All paths remain inside workspace", started);
    } catch (IOException exception) {
      return evidence(VerificationStatus.FAILED, "Unable to inspect workspace integrity", started);
    }
  }

  private static VerificationEvidence evidence(
      VerificationStatus status, String summary, Instant started) {
    return new VerificationEvidence(
        "workspace_integrity",
        status,
        true,
        false,
        "",
        null,
        summary,
        "",
        Duration.between(started, Instant.now()));
  }
}

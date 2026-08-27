package io.legacypilot.verification;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class DiffPolicyCheck implements VerificationCheck {

  private final int maximumChangedLines;
  private final List<String> protectedGlobs;

  public DiffPolicyCheck(int maximumChangedLines, List<String> protectedGlobs) {
    if (maximumChangedLines < 1) {
      throw new IllegalArgumentException("maximum changed lines must be positive");
    }
    this.maximumChangedLines = maximumChangedLines;
    this.protectedGlobs = List.copyOf(Objects.requireNonNull(protectedGlobs));
  }

  @Override
  public VerificationEvidence verify(VerificationContext context) {
    var started = Instant.now();
    var result =
        context
            .tools()
            .execute("git_diff", context.toolContext(), JsonNodeFactory.instance.objectNode());
    if (!result.successful()) {
      return evidence(
          VerificationStatus.FAILED,
          true,
          "Unable to inspect diff: " + result.error().message(),
          started);
    }
    var output = result.output();
    if (output.path("truncated").asBoolean()) {
      return evidence(
          VerificationStatus.BLOCKED,
          false,
          "Diff was truncated; change scope is unknown",
          started);
    }
    int changed = 0;
    String protectedPath = null;
    for (var line : output.path("numstat").asText("").lines().toList()) {
      var fields = line.split("\\t", 3);
      if (fields.length != 3) {
        continue;
      }
      changed += count(fields[0]) + count(fields[1]);
      if (protectedPath == null && isProtected(fields[2])) {
        protectedPath = fields[2];
      }
    }
    if (protectedPath != null) {
      return evidence(
          VerificationStatus.BLOCKED, false, "Protected path changed: " + protectedPath, started);
    }
    if (changed > maximumChangedLines) {
      return evidence(
          VerificationStatus.BLOCKED,
          false,
          "Changed lines " + changed + " exceed limit " + maximumChangedLines,
          started);
    }
    return evidence(
        VerificationStatus.PASSED,
        false,
        "Diff scope accepted: " + changed + " changed lines",
        started);
  }

  private boolean isProtected(String value) {
    Path path = Path.of(value.replace(" => ", ""));
    return protectedGlobs.stream()
        .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
        .anyMatch(matcher -> matcher.matches(path));
  }

  private static int count(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private static VerificationEvidence evidence(
      VerificationStatus status, boolean repairable, String summary, Instant started) {
    return new VerificationEvidence(
        "diff-policy",
        status,
        true,
        repairable,
        "git_diff",
        null,
        summary,
        "",
        Duration.between(started, Instant.now()));
  }
}

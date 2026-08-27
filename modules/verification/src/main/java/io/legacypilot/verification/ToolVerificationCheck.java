package io.legacypilot.verification;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;

public final class ToolVerificationCheck implements VerificationCheck {

  private final String name;
  private final String tool;
  private final JsonNode input;
  private final boolean required;
  private final boolean repairable;

  public ToolVerificationCheck(
      String name, String tool, JsonNode input, boolean required, boolean repairable) {
    this.name = Objects.requireNonNull(name);
    this.tool = Objects.requireNonNull(tool);
    this.input = Objects.requireNonNull(input).deepCopy();
    this.required = required;
    this.repairable = repairable;
  }

  @Override
  public VerificationEvidence verify(VerificationContext context) {
    var started = Instant.now();
    var result = context.tools().execute(tool, context.toolContext(), input);
    var output = result.output();
    var exitCode =
        output == null || output.path("exitCode").isMissingNode()
            ? null
            : output.path("exitCode").isNull() ? null : output.path("exitCode").asInt();
    var summary =
        result.successful()
            ? BuildOutputParser.summarize(
                output == null ? "completed" : output.path("output").asText(output.toString()))
            : bounded(result.error().message());
    return new VerificationEvidence(
        name,
        result.successful() ? VerificationStatus.PASSED : VerificationStatus.FAILED,
        required,
        repairable,
        tool,
        exitCode,
        summary,
        "",
        java.time.Duration.between(started, Instant.now()));
  }

  private static String bounded(String value) {
    return value.length() <= 2_000 ? value : value.substring(0, 2_000) + "…[TRUNCATED]";
  }
}

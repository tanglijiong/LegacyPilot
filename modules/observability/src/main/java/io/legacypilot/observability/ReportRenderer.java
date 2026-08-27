package io.legacypilot.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;

public final class ReportRenderer {

  private final ObjectMapper mapper;

  public ReportRenderer(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper);
  }

  public String json(RunReport report) {
    try {
      return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to render JSON report", exception);
    }
  }

  public String markdown(RunReport report) {
    var output = new StringBuilder();
    output.append("# LegacyPilot Run ").append(report.runId()).append("\n\n");
    output.append("- Status: **").append(report.status()).append("**\n");
    output.append("- Risk: **").append(report.risk()).append("**\n");
    output.append("- Steps: ").append(report.steps()).append("\n");
    output.append("- Model tokens: ").append(report.modelTokens()).append("\n");
    output.append("- Estimated cost: $").append(report.estimatedCostUsd()).append("\n\n");
    output.append("## Summary\n\n").append(report.summary()).append("\n\n");
    output.append("## Plan\n\n");
    report.plan().forEach(step -> output.append("- ").append(step).append("\n"));
    output.append("\n## Verification\n\n");
    report
        .verification()
        .forEach(
            check ->
                output
                    .append("- ")
                    .append(check.getOrDefault("name", "check"))
                    .append(": ")
                    .append(check.getOrDefault("status", "unknown"))
                    .append(" — ")
                    .append(check.getOrDefault("evidence", ""))
                    .append("\n"));
    return output.toString();
  }
}

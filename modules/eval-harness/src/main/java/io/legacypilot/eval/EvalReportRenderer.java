package io.legacypilot.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;

public final class EvalReportRenderer {
  private final ObjectMapper mapper;

  public EvalReportRenderer(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper);
  }

  public String json(EvalSummary summary) {
    try {
      return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("unable to render eval report", exception);
    }
  }

  public String markdown(EvalSummary summary) {
    var output =
        new StringBuilder("# LegacyPilot Eval ").append(summary.datasetVersion()).append("\n\n");
    output.append("- Model: ").append(summary.model()).append('\n');
    output
        .append("- Success rate: ")
        .append(String.format(java.util.Locale.ROOT, "%.0f%%", summary.successRate() * 100))
        .append('\n');
    output
        .append("- Average retrieval recall: ")
        .append(String.format(java.util.Locale.ROOT, "%.2f", summary.averageRecall()))
        .append("\n\n");
    output.append("| Task | Status | Assertions | Compile | Tests | Recall | Steps | Tokens |\n");
    output.append("| --- | --- | ---: | --- | --- | ---: | ---: | ---: |\n");
    summary
        .results()
        .forEach(
            result ->
                output
                    .append("| ")
                    .append(result.taskId())
                    .append(" | ")
                    .append(result.status())
                    .append(" | ")
                    .append(result.passedAssertions())
                    .append('/')
                    .append(result.totalAssertions())
                    .append(" | ")
                    .append(result.compiled())
                    .append(" | ")
                    .append(result.testsPassed())
                    .append(" | ")
                    .append(String.format(java.util.Locale.ROOT, "%.2f", result.retrievalRecall()))
                    .append(" | ")
                    .append(result.steps())
                    .append(" | ")
                    .append(result.tokens())
                    .append(" |\n"));
    return output.toString();
  }
}

package io.legacypilot.verification;

import java.util.regex.Pattern;

public final class BuildOutputParser {

  private static final Pattern TESTS =
      Pattern.compile("Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+), Skipped: (\\d+)");
  private static final Pattern COVERAGE =
      Pattern.compile(
          "(?:coverage|covered).*?([0-9]{1,3}(?:\\.[0-9]+)?)%", Pattern.CASE_INSENSITIVE);

  private BuildOutputParser() {}

  public static String summarize(String output) {
    if (output == null || output.isBlank()) {
      return "No build output was produced";
    }
    var summary = new StringBuilder();
    var tests = TESTS.matcher(output);
    int suites = 0;
    int run = 0;
    int failures = 0;
    int errors = 0;
    int skipped = 0;
    while (tests.find()) {
      suites++;
      run += Integer.parseInt(tests.group(1));
      failures += Integer.parseInt(tests.group(2));
      errors += Integer.parseInt(tests.group(3));
      skipped += Integer.parseInt(tests.group(4));
    }
    if (suites > 0) {
      summary
          .append("tests=")
          .append(run)
          .append(", failures=")
          .append(failures)
          .append(", errors=")
          .append(errors)
          .append(", skipped=")
          .append(skipped);
    }
    var coverage = COVERAGE.matcher(output);
    if (coverage.find()) {
      if (!summary.isEmpty()) {
        summary.append("; ");
      }
      summary.append("coverage=").append(coverage.group(1)).append('%');
    }
    if (summary.isEmpty()) {
      var status = output.contains("BUILD SUCCESS") ? "BUILD SUCCESS" : lastMeaningfulLine(output);
      summary.append(status);
    }
    return bounded(summary.toString());
  }

  private static String lastMeaningfulLine(String output) {
    var lines = output.lines().filter(line -> !line.isBlank()).toList();
    return lines.isEmpty() ? "Build output unavailable" : lines.getLast().trim();
  }

  private static String bounded(String value) {
    return value.length() <= 2_000 ? value : value.substring(0, 2_000) + "…[TRUNCATED]";
  }
}

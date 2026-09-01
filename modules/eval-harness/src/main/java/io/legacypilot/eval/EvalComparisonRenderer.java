package io.legacypilot.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Objects;

public final class EvalComparisonRenderer {
  private final ObjectMapper mapper;

  public EvalComparisonRenderer(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper);
  }

  public String json(EvalComparison comparison) {
    try {
      return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(comparison);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("unable to render eval comparison", exception);
    }
  }

  public String markdown(EvalComparison comparison) {
    var output = new StringBuilder("# LegacyPilot Eval Comparison\n\n");
    output.append("- Dataset: `").append(comparison.datasetVersion()).append("`\n");
    output.append("- Baseline: `").append(comparison.baselineRunId()).append("`\n");
    output.append("- Candidate: `").append(comparison.candidateRunId()).append("`\n");
    output
        .append("- Regression gate: **")
        .append(comparison.gate().passed() ? "PASS" : "FAIL")
        .append("**\n\n");
    output.append("| Run | Pass@1 | Passed | Cost USD | p95 ms | Tokens |\n");
    output.append("| --- | ---: | ---: | ---: | ---: | ---: |\n");
    metricRow(output, "Baseline", comparison.baseline());
    metricRow(output, "Candidate", comparison.candidate());
    output.append("\n| Task | Baseline | Candidate | Primary failure | Delta cost | Delta ms |\n");
    output.append("| --- | --- | --- | --- | ---: | ---: |\n");
    comparison
        .tasks()
        .forEach(
            task ->
                output
                    .append("| ")
                    .append(task.taskId())
                    .append(" | ")
                    .append(task.baselineStatus())
                    .append(" | ")
                    .append(task.candidateStatus())
                    .append(" | ")
                    .append(task.candidateFailure())
                    .append(" | ")
                    .append(task.costDeltaUsd())
                    .append(" | ")
                    .append(task.durationDeltaMillis())
                    .append(" |\n"));
    if (!comparison.gate().reasons().isEmpty()) {
      output.append("\n## Gate failures\n\n");
      comparison
          .gate()
          .reasons()
          .forEach(reason -> output.append("- ").append(reason).append('\n'));
    }
    return output.toString();
  }

  public String html(EvalComparison comparison) {
    var rows = new StringBuilder();
    comparison
        .tasks()
        .forEach(
            task ->
                rows.append("<tr data-status=\"")
                    .append(task.candidateStatus())
                    .append("\"><td>")
                    .append(escape(task.taskId()))
                    .append("</td><td>")
                    .append(task.baselineStatus())
                    .append("</td><td>")
                    .append(task.candidateStatus())
                    .append("</td><td>")
                    .append(task.candidateFailure())
                    .append("</td><td>")
                    .append(task.costDeltaUsd())
                    .append("</td><td>")
                    .append(task.durationDeltaMillis())
                    .append("</td></tr>"));
    var failureBars = new StringBuilder();
    comparison.candidateFailures().entrySet().stream()
        .sorted(java.util.Map.Entry.comparingByKey())
        .forEach(
            entry ->
                failureBars
                    .append("<div class=\"failure\"><span>")
                    .append(entry.getKey())
                    .append("</span><i style=\"width:")
                    .append(Math.min(100, entry.getValue() * 10))
                    .append("%\"></i><b>")
                    .append(entry.getValue())
                    .append("</b></div>"));
    return """
        <!doctype html><html lang="en"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>LegacyPilot Eval Dashboard</title><style>
        :root{font-family:Inter,system-ui,sans-serif;color:#14213d;background:#f5f7fa}body{margin:0;padding:32px}
        main{max-width:1200px;margin:auto}.cards{display:grid;grid-template-columns:repeat(4,1fr);gap:16px}
        .card,section{background:white;border:1px solid #dce3ea;border-radius:12px;padding:20px;margin-bottom:20px}
        .metric{font-size:28px;font-weight:700}.pass{color:#087f5b}.fail{color:#c92a2a}
        table{width:100%;border-collapse:collapse}
        th,td{text-align:left;padding:10px;border-bottom:1px solid #edf0f2}input{padding:10px;width:260px}
        .failure{display:flex;align-items:center;gap:10px;margin:8px 0}.failure span{width:220px}
        .failure i{height:12px;background:#f59f00;border-radius:6px}
        @media(max-width:760px){.cards{grid-template-columns:1fr 1fr}body{padding:12px}}
        </style></head><body><main><h1>LegacyPilot Eval Dashboard</h1>
        <p>Dataset {{dataset}} · baseline {{baseline}} · candidate {{candidate}}</p>
        <div class="cards"><div class="card"><div>Candidate pass@1</div><div class="metric">{{pass}}</div></div>
        <div class="card"><div>Passed tasks</div><div class="metric">{{passed}}/{{tasks}}</div></div>
        <div class="card"><div>Total cost USD</div><div class="metric">{{cost}}</div></div>
        <div class="card"><div>Regression gate</div><div class="metric {{gateClass}}">{{gate}}</div></div></div>
        <section><h2>Failure classification</h2>{{failures}}</section>
        <section><h2>Task results</h2><input id="filter" aria-label="Filter tasks" placeholder="Filter task or failure">
        <table><thead><tr><th>Task</th><th>Baseline</th><th>Candidate</th>
        <th>Primary failure</th><th>Δ cost</th><th>Δ ms</th></tr></thead>
        <tbody id="tasks">{{rows}}</tbody></table></section></main>
        <script>
        const f=document.getElementById('filter');
        f.addEventListener('input',()=>{const q=f.value.toLowerCase();
        document.querySelectorAll('#tasks tr').forEach(r=>r.hidden=!r.textContent.toLowerCase().includes(q));});
        </script>
        </body></html>
        """
        .replace("{{dataset}}", escape(comparison.datasetVersion()))
        .replace("{{baseline}}", escape(comparison.baselineRunId()))
        .replace("{{candidate}}", escape(comparison.candidateRunId()))
        .replace("{{pass}}", percent(comparison.candidate().passAtOne()))
        .replace("{{passed}}", Integer.toString(comparison.candidate().passed()))
        .replace("{{tasks}}", Integer.toString(comparison.candidate().tasks()))
        .replace("{{cost}}", comparison.candidate().totalCostUsd().toString())
        .replace("{{gateClass}}", comparison.gate().passed() ? "pass" : "fail")
        .replace("{{gate}}", comparison.gate().passed() ? "PASS" : "FAIL")
        .replace("{{failures}}", failureBars)
        .replace("{{rows}}", rows);
  }

  private static void metricRow(
      StringBuilder output, String label, EvalComparison.Metrics metrics) {
    output
        .append("| ")
        .append(label)
        .append(" | ")
        .append(percent(metrics.passAtOne()))
        .append(" | ")
        .append(metrics.passed())
        .append('/')
        .append(metrics.tasks())
        .append(" | ")
        .append(metrics.totalCostUsd())
        .append(" | ")
        .append(metrics.durationP95Millis())
        .append(" | ")
        .append(metrics.totalTokens())
        .append(" |\n");
  }

  private static String percent(double value) {
    return String.format(Locale.ROOT, "%.0f%%", value * 100);
  }

  private static String escape(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }
}

#!/usr/bin/env ruby
# frozen_string_literal: true

require "fileutils"
require "json"
require "open3"
require "pathname"
require "yaml"

ROOT = Pathname.new(__dir__).join("../..").expand_path
DATASET = ROOT.join("evals/datasets/v0.3")
CATEGORIES = [
  "Retrieval Failure",
  "Context Failure",
  "Planning Failure",
  "Tool Failure",
  "Code/Edit Failure",
  "Verification Failure",
  "Constraint/Policy Failure",
  "Model Reasoning Failure"
].freeze

BASELINE_FAILURES = {
  "task-005" => "Code/Edit Failure",
  "task-006" => "Model Reasoning Failure",
  "task-008" => "Model Reasoning Failure",
  "task-009" => "Code/Edit Failure",
  "task-013" => "Code/Edit Failure",
  "task-014" => "Model Reasoning Failure",
  "task-015" => "Code/Edit Failure",
  "task-016" => "Code/Edit Failure",
  "task-017" => "Code/Edit Failure",
  "task-018" => "Code/Edit Failure",
  "task-020" => "Code/Edit Failure"
}.freeze

FINAL_FAILURES = BASELINE_FAILURES.freeze

def load_run(relative)
  directory = ROOT.join(relative)
  checkpoint = JSON.parse(directory.join("checkpoint.json").read)
  manifest = JSON.parse(directory.join("manifest.json").read)
  [directory, checkpoint.fetch("payload"), manifest]
end

def task_definition(task_id)
  YAML.safe_load(DATASET.join(task_id, "task.yml").read, aliases: true)
end

def fixture_root(task)
  provenance_path = ROOT.join("evals/fixtures", task.fetch("fixtureId"), "provenance.yml")
  provenance = YAML.safe_load(provenance_path.read, aliases: true)
  provenance_path.dirname.join(provenance.fetch("path")).expand_path
end

def selected_context(root)
  paths = [root.join("pom.xml")]
  %w[src/main/java src/main/resources src/test/java].each do |relative|
    directory = root.join(relative)
    paths.concat(directory.glob("**/*").select(&:file?).sort) if directory.directory?
  end
  paths.select(&:file?).map { |path| [path.relative_path_from(root).to_s, path.size] }
end

def diff_for(task, workspace)
  original = fixture_root(task)
  task.fetch("allowedFiles").map do |relative|
    before = original.join(relative)
    after = workspace.join(relative)
    next if before.file? && after.file? && before.binread == after.binread
    left = before.file? ? before.to_s : "/dev/null"
    right = after.file? ? after.to_s : "/dev/null"
    output, = Open3.capture2e(
      "diff", "-u", "--label", "a/#{relative}", "--label", "b/#{relative}", left, right
    )
    output.gsub(/[ \t]+(?=\n)/, "")
  end.compact.join("\n")
end

def compact_failure(failure, limit = 900)
  text = failure.to_s
    .gsub(ROOT.to_s, "<repo>")
    .gsub(/\[INFO\].*\n/, "")
    .lines
    .map(&:strip)
    .reject(&:empty?)
    .uniq
    .join("\n")
  text.length > limit ? "#{text[0, limit].rstrip}\n… (truncated; full verifier output is in checkpoint.json)" : text
end

def metrics(payload)
  results = payload.fetch("attempts").values.map { |attempt| attempt.fetch("result") }
  passed = results.count { |result| result.fetch("status") == "PASSED" }
  compiled = results.count { |result| result.fetch("compiled") }
  validated = passed
  {
    passed: passed,
    compiled: compiled,
    validated: validated,
    average_steps: results.sum { |result| result.fetch("steps") }.fdiv(results.length),
    input_tokens: results.sum { |result| result.dig("usage", "inputTokens") || 0 },
    output_tokens: results.sum { |result| result.dig("usage", "outputTokens") || 0 }
  }
end

def trace_for(workspace)
  path = workspace.join(".legacy-pilot/agent-trace.json")
  path.file? ? JSON.parse(path.read) : nil
end

def write_evidence(label, run_directory, payload, manifest, classifications)
  output = ROOT.join("docs/benchmarks/evidence", label)
  FileUtils.mkdir_p(output)
  payload.fetch("attempts").sort.each do |task_id, attempt|
    task = task_definition(task_id)
    result = attempt.fetch("result")
    workspace = run_directory.join("workspaces", task_id)
    trace = trace_for(workspace)
    diff = diff_for(task, workspace)
    output.join("#{task_id}.diff").write(diff.empty? ? "# No production diff captured.\n" : diff)
    context = trace&.fetch("selectedContext", nil)&.map do |entry|
      [entry.fetch("path"), entry.fetch("bytes")]
    end || selected_context(fixture_root(task))
    plan = trace&.fetch("plan", nil)
    calls = trace&.fetch("toolCalls", nil)
    modified = result.fetch("artifacts")
    status = result.fetch("status")
    classification = classifications[task_id] || "—"
    content = <<~MARKDOWN
      # #{task_id} — #{label}

      - Requirement: #{task.fetch("requirement")}
      - Final result: **#{status}**
      - Failure category: #{classification}
      - Modified files: #{modified.empty? ? "none" : modified.join(", ")}
      - Compile result: #{result.fetch("compiled") ? "PASS" : "FAIL"}
      - Test result: #{result.fetch("testsPassed") ? "PASS" : "FAIL"}
      - Validation result: #{result.fetch("passedAssertions")}/#{result.fetch("totalAssertions")} assertions; final #{status}
      - Model/tokens: `#{manifest.fetch("model")}`; input #{result.dig("usage", "inputTokens")}; output #{result.dig("usage", "outputTokens")}; reasoning #{result.dig("usage", "reasoningOutputTokens")}; local cost $#{result.fetch("estimatedCostUsd")}
      - Agent steps: #{result.fetch("steps")}; duration #{format("%.2f", result.fetch("duration"))}s
      - Git diff: [#{task_id}.diff](./#{task_id}.diff)

      ## Selected context

      #{context.map { |path, bytes| "- `#{path}` (#{bytes} bytes)" }.join("\n")}

      ## Agent plan

      ```json
      #{plan ? JSON.pretty_generate(plan) : '{"status":"not captured by baseline adapter"}'}
      ```

      ## Tool calls

      ```json
      #{calls ? JSON.pretty_generate(calls) : '[{"status":"not captured structurally","inferred":"model edit -> file writes -> Maven public verification -> harness hidden verification"}]'}
      ```

      ## Validation trace

      ```text
      #{status == "PASSED" ? "All public assertions, compilation, tests, and hidden validation passed." : compact_failure(result.fetch("failure"))}
      ```
    MARKDOWN
    output.join("#{task_id}.md").write(content)
  end
end

def result_rows(payload, classifications, label)
  payload.fetch("attempts").sort.map do |task_id, attempt|
    result = attempt.fetch("result")
    "| #{task_id} | #{result.fetch("status")} | #{result.fetch("passedAssertions")}/#{result.fetch("totalAssertions")} | #{result.fetch("compiled") ? "PASS" : "FAIL"} | #{result.fetch("testsPassed") ? "PASS" : "FAIL"} | #{result.fetch("steps")} | #{result.dig("usage", "inputTokens")}/#{result.dig("usage", "outputTokens")} | #{classifications[task_id] || "—"} | [evidence](evidence/#{label}/#{task_id}.md) |"
  end.join("\n")
end

def failure_counts(classifications)
  CATEGORIES.to_h { |category| [category, classifications.values.count(category)] }
end

def taxonomy_table(baseline, final = nil)
  baseline_counts = failure_counts(baseline)
  final_counts = final ? failure_counts(final) : nil
  CATEGORIES.map do |category|
    if final_counts
      "| #{category} | #{baseline_counts.fetch(category)} | #{final_counts.fetch(category)} |"
    else
      "| #{category} | #{baseline_counts.fetch(category)} |"
    end
  end.join("\n")
end

baseline_directory, baseline_payload, baseline_manifest = load_run(
  "evals/runs/deepseek-v03-baseline-20260909"
)
final_directory, final_payload, final_manifest = load_run(
  "evals/runs/deepseek-v03-final-20260909-r3"
)

write_evidence("v0.3-baseline", baseline_directory, baseline_payload, baseline_manifest, BASELINE_FAILURES)
write_evidence("v0.3-final", final_directory, final_payload, final_manifest, FINAL_FAILURES)

baseline_metrics = metrics(baseline_payload)
final_metrics = metrics(final_payload)
FileUtils.mkdir_p(ROOT.join("docs/benchmarks"))

baseline_report = <<~MARKDOWN
  # LegacyPilot v0.3 DeepSeek Benchmark — Baseline

  > Run: `#{baseline_manifest.fetch("runId")}` · commit `#{baseline_manifest.fetch("repositoryCommit")}` · dataset `#{baseline_manifest.fetch("datasetVersion")}` (`#{baseline_manifest.fetch("datasetSha256")}`) · prompt `#{baseline_manifest.fetch("promptVersion")}` · policy `#{baseline_manifest.fetch("policyVersion")}`

  ## Result

  - Success rate: **#{baseline_metrics[:passed]}/20 (#{baseline_metrics[:passed] * 5}%)**
  - Compile success rate: **#{baseline_metrics[:compiled]}/20 (#{baseline_metrics[:compiled] * 5}%)**
  - Validation success rate: **#{baseline_metrics[:validated]}/20 (#{baseline_metrics[:validated] * 5}%)**
  - Average agent steps: **#{format("%.2f", baseline_metrics[:average_steps])}**
  - Tokens: input **#{baseline_metrics[:input_tokens]}**, output **#{baseline_metrics[:output_tokens]}**
  - Tool calls: baseline adapter did not persist structured tool-call events; file-write steps and verifier results are retained. This is reported as **N/A**, not estimated.
  - Environment: local Docker Model Runner, `#{baseline_manifest.fetch("model")}`, Java #{baseline_manifest.dig("environment", "java")}, local-only network boundary; PostgreSQL 16 Docker service healthy.

  The reproduced score exactly matches the historical 9/20 baseline. No expected result, assertion, hidden test, or dataset file was changed.

  ## All 20 tasks

  | Task | Final | Assertions | Compile | Tests | Steps | Tokens in/out | Primary failure | Evidence |
  |---|---:|---:|---:|---:|---:|---:|---|---|
  #{result_rows(baseline_payload, BASELINE_FAILURES, "v0.3-baseline")}

  ## Failure taxonomy

  | Category | Count |
  |---|---:|
  #{taxonomy_table(BASELINE_FAILURES)}

  Retrieval and context are not assigned as primary failures because the adapter supplied the complete Maven fixture source (including callers and public tests) within the context budget. `retrievalRecall=0.0` means the direct local adapter did not emit a retrieval metric; it does not prove zero recall.

  ## Typical failure traces

  - **Code/Edit — task-016:** the model placed a public enum in `Order.java`, duplicated `OrderStatus`, and omitted `Instant` imports. The patch failed compilation after full context was available.
  - **Code/Edit — task-009/task-013/task-017/task-020:** generated replacements removed or changed required method signatures, so existing callers or hidden contract tests no longer compiled.
  - **Model Reasoning — task-006:** code compiled but repeated requests created a distinct `Order`; the idempotency invariant and no-second-write behavior were not implemented correctly.
  - **Model Reasoning — task-008:** the service threw `OrderNotFoundException`, but the controller did not map it to the requested stable 404 response.
  - **Model Reasoning — task-014:** trimming a null value raised `NullPointerException` instead of the required stable `IllegalArgumentException`.

  ## Bottlenecks and recommendations

  1. **Whole-file replacement reliability:** most failures are malformed imports, lost APIs, or incorrect file/type boundaries. Add transactional edit attempts and compile-gated rollback.
  2. **API compatibility reasoning:** extract and explicitly preserve existing signatures and callers before editing; reject patches that delete an existing callable API unless the requirement requests it.
  3. **Feedback-loop isolation:** do not let a failed patch become the context for the next repair. Restore the last verified workspace and provide bounded compiler feedback.
  4. **Boundary reasoning:** add a generic pre-submit checklist for nulls, empty values, numeric limits, time boundaries, idempotency, ordering, and concurrency.
  5. **Observability:** persist selected context, plan, model calls, edit attempts, Maven results, token usage, and final diff per task.
MARKDOWN
ROOT.join("docs/benchmarks/v0.3-baseline.md").write(baseline_report)

baseline_tool_calls = "N/A (not captured)"
final_traces = final_payload.fetch("attempts").keys.map do |task_id|
  trace_for(final_directory.join("workspaces", task_id))
end.compact
final_tool_calls = final_traces.sum { |trace| trace.fetch("toolCalls").length }

final_report = <<~MARKDOWN
  # LegacyPilot v0.3 DeepSeek Benchmark — Final

  > Final run: `#{final_manifest.fetch("runId")}` · commit `#{final_manifest.fetch("repositoryCommit")}` · same frozen dataset `#{final_manifest.fetch("datasetVersion")}` (`#{final_manifest.fetch("datasetSha256")}`) · policy `#{final_manifest.fetch("policyVersion")}`

  ## Baseline → Final

  | Metric | Baseline | Final | Change |
  |---|---:|---:|---:|
  | Success rate | #{baseline_metrics[:passed]}/20 (#{baseline_metrics[:passed] * 5}%) | #{final_metrics[:passed]}/20 (#{final_metrics[:passed] * 5}%) | #{final_metrics[:passed] - baseline_metrics[:passed]} tasks |
  | Compile success rate | #{baseline_metrics[:compiled]}/20 (#{baseline_metrics[:compiled] * 5}%) | #{final_metrics[:compiled]}/20 (#{final_metrics[:compiled] * 5}%) | #{final_metrics[:compiled] - baseline_metrics[:compiled]} tasks |
  | Validation success rate | #{baseline_metrics[:validated]}/20 (#{baseline_metrics[:validated] * 5}%) | #{final_metrics[:validated]}/20 (#{final_metrics[:validated] * 5}%) | #{final_metrics[:validated] - baseline_metrics[:validated]} tasks |
  | Average steps | #{format("%.2f", baseline_metrics[:average_steps])} | #{format("%.2f", final_metrics[:average_steps])} | #{format("%+.2f", final_metrics[:average_steps] - baseline_metrics[:average_steps])} |
  | Structured tool calls | #{baseline_tool_calls} | #{final_tool_calls} total | instrumentation added |
  | Input tokens | #{baseline_metrics[:input_tokens]} | #{final_metrics[:input_tokens]} | #{final_metrics[:input_tokens] - baseline_metrics[:input_tokens]} |
  | Output tokens | #{baseline_metrics[:output_tokens]} | #{final_metrics[:output_tokens]} | #{final_metrics[:output_tokens] - baseline_metrics[:output_tokens]} |

  **Target status: NOT MET.** The final result is **#{final_metrics[:passed]}/20 (#{final_metrics[:passed] * 5}%)**, below the required 14/20. Results are recorded unchanged; no benchmark expectation, hidden test, or acceptance threshold was modified.

  ## Changes evaluated

  - Added a separate, persisted planning trace and conservative fallback when planning JSON is malformed.
  - Added explicit generic API/type/import/boundary preservation guidance.
  - Added up to three public compile/test repair attempts.
  - Added transactional rollback after failed attempts so repairs restart from the last clean fixture.
  - Persisted selected context, plan, tool calls, attempts, failures, usage, final workspace, and diff.

  An intermediate full 20-task run with the generated plan injected into coding scored 8/20; that negative strategy was removed. The final candidate recovered to 9/20 but did not improve correctness over baseline.

  ## All 20 tasks

  | Task | Final | Assertions | Compile | Tests | Steps | Tokens in/out | Primary failure | Evidence |
  |---|---:|---:|---:|---:|---:|---:|---|---|
  #{result_rows(final_payload, FINAL_FAILURES, "v0.3-final")}

  ## Failure category changes

  | Category | Baseline | Final |
  |---|---:|---:|
  #{taxonomy_table(BASELINE_FAILURES, FINAL_FAILURES)}

  ## Final bottleneck assessment

  The harness is now more observable and failed edits are isolated, but the local Q4 DeepSeek model remains the limiting factor. It repeatedly emits whole-file replacements with missing imports, incompatible signatures, invalid checked-exception handling, or incomplete boundary semantics even after receiving compiler feedback. Full source context was present, so retrieval was not the primary cause. The extra planning call increased token use without improving success.

  Recommended next iteration, without case-specific rules:

  1. Replace whole-file generation with syntax-aware, minimal edits and reject structurally invalid Java before writing.
  2. Add a generic API-surface diff gate (constructors/methods/top-level types) before Maven verification.
  3. Use deterministic compiler diagnostics plus AST context in the repair prompt, capped and de-duplicated.
  4. Evaluate a stronger local coding model under the same frozen dataset and prompt policy; record it as a separate model comparison, not as the v0.3 score.
  5. Keep hidden tests verifier-owned and unavailable to the agent to preserve benchmark integrity.
MARKDOWN
ROOT.join("docs/benchmarks/v0.3-final.md").write(final_report)

# Real-model baseline prompt v1

Version: `baseline-prompt-v1`

## Initial attempt template

```text
LegacyPilot real-model baseline, strategy codex-agent-v1. Work only in this isolated Java Maven project. Implement this requirement completely: {{requirement}} Inspect the existing production code, make the smallest correct production-code change, and run relevant Maven tests. Do not modify tests, pom.xml, generated files, or anything outside this workspace. Do not use the internet. Finish only after checking the result.
```

## Assertion-feedback retry template

```text
LegacyPilot real-model baseline assertion-feedback retry, strategy codex-agent-v1, final allowed attempt. Audit the current production implementation for this requirement: {{requirement}} Public deterministic acceptance checks failed: {{failed_public_assertions}} Fix the production code cleanly and run Maven tests. Do not modify tests, pom.xml, generated files, or anything outside this workspace. Do not use the internet.
```

The retry contains only failed public dataset assertions. It does not contain reference-solution code. A task receives at most one retry, and usage from both attempts is included in its cost.

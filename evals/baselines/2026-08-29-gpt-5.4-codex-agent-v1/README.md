# GPT-5.4 real-model baseline — 2026-08-29

## Result

- Dataset: `v0.1`, fixture revision `banking-fixture-v2`
- Target: at least 4 of 5 tasks
- First attempt: 1/5
- Final result after the one allowed public-assertion feedback retry: **5/5**
- Every final task passed all dataset assertions and an independent Maven test run.
- The fixture `pom.xml` and test sources were byte-for-byte unchanged in all five workspaces.

The first-attempt result is retained because the final score depends materially on the retry policy. Tasks 002–005 each used their one allowed retry; task 001 did not retry. Failed public assertions were returned to the model, but reference solutions were never exposed.

## Model and strategy

- Provider/authentication: OpenAI Codex using the existing ChatGPT account credential
- Requested model alias: `gpt-5.4`
- Reasoning effort: `high`
- Prompt version: `baseline-prompt-v1`
- Prompt SHA-256: `2913d760a67f00d38dcdcabb8934c8422928e4d70e35030dde570db1606e056a`
- Strategy version: `codex-agent-v1`
- Execution: isolated copy per task, concurrency 2, `workspace-write` sandbox, no model-side internet use
- Retry policy: at most one retry containing only failed public deterministic assertions

The official fixed snapshot `gpt-5.4-2026-03-05` was tested first, but the ChatGPT-account Codex channel rejected snapshot identifiers. The accepted `gpt-5.4` alias was therefore used, and this limitation is recorded rather than claiming snapshot-level reproducibility. The exact prompt templates are in [prompt.md](prompt.md), and machine-readable evidence is in [summary.json](summary.json).

## Usage and price

| Task | Attempts | Input | Cached input | Output | API-equivalent USD |
| --- | ---: | ---: | ---: | ---: | ---: |
| task-001 | 1 | 155,182 | 135,680 | 2,588 | $0.121495 |
| task-002 | 2 | 271,969 | 235,776 | 4,601 | $0.218442 |
| task-003 | 2 | 271,147 | 242,432 | 4,188 | $0.195216 |
| task-004 | 2 | 410,017 | 370,432 | 7,787 | $0.308376 |
| task-005 | 2 | 562,814 | 508,672 | 11,360 | $0.432923 |
| **Total** | **9** | **1,671,129** | **1,492,992** | **30,524** | **$1.276451** |

Pricing uses the official GPT-5.4 standard API rates current on the run date: `$2.50/M` uncached input, `$0.25/M` cached input, and `$15/M` output. Source: [OpenAI GPT-5.4 model documentation](https://developers.openai.com/api/docs/models/gpt-5.4).

This is an API-equivalent estimate. The run used ChatGPT-account Codex authentication, which did not produce a per-request API invoice, so the actual incremental billed amount is not observable from this run. The small preflight calibration call is excluded from the task baseline total; its API-equivalent cost was `$0.025626`.

## Environment

| Item | Value |
| --- | --- |
| Repository commit | `7617a02f7e7e99351897e2e899ce18d9a203ec2b` |
| Fixture SHA-256 | `5d01504b7e0c165e2351f24b39b8f81f927bbf0cbe1b6221823038c3aef69704` |
| OS / architecture | macOS 15.5 / arm64 |
| Evaluation Java | OpenJDK 21.0.12.1 |
| Host default Java | OpenJDK 17.0.15 |
| Maven | 3.9.16 via project wrapper |
| Codex CLI | 0.144.1 |
| Docker | Docker Desktop 28.4.0 running, not used by this baseline |
| Run window | 2026-08-29 10:18:27–10:33:03, UTC+06:00 |

Intermittent TLS, WebSocket, plugin-catalog, and analytics transport warnings occurred during the run. Codex's bounded provider retry recovered and all task processes exited successfully; the warnings are recorded as an environment limitation.

# v0.2.0 Release Checklist

## Engineering scope

- [x] Issues 21–26 durable recovery and long-task harness implemented
- [x] Issues 27–32 governed tools, model resilience, retrieval and Docker dependency governance implemented
- [x] Migration, recovery, governed harness and known-limit documentation present
- [x] v0.1 historical release state and roadmap corrected
- [x] Dependabot major updates reviewed and kept out of the release candidate
- [x] Docker Compose mismatch explicitly deferred with a supported local/H2 path

## Candidate verification

- [x] Maven project version is `0.2.0` with no stale `0.1.0-SNAPSHOT` runtime commands
- [x] JDK 21 full `clean verify` passes: 127 tests, 0 failures, 0 errors, 0 skipped
- [x] Real Docker integration tests execute 2/2 with no skips
- [x] Fresh-clone Quickstart completes within 30 minutes: 12m55s on `fa39f54`
- [x] Five-task real-model regression is recorded against the commit containing Issues 21–32
- [x] Model, Prompt/strategy, price and environment are recorded
- [x] CI, Security and Eval smoke pass on the exact release commit: [CI](https://github.com/tanglijiong/LegacyPilot/actions/runs/33248697215), [Security](https://github.com/tanglijiong/LegacyPilot/actions/runs/33248697211), [Eval smoke](https://github.com/tanglijiong/LegacyPilot/actions/runs/33248697212)
- [x] No credentials, reference overlay, local absolute paths or unreviewed generated artifacts are committed

Local candidate verification ran on 2026-08-29 with OpenJDK 21.0.12.1, Maven Wrapper and Docker Engine 28.4.0 on macOS arm64. After correcting the MCP-reported release version, the final full 24-module reactor completed in 5m14s; 127 tests passed and the Docker integration class completed 2/2 in 237.6s with no skips.

## Publication

- [x] Annotated `v0.2.0` tag points to verified release commit `6675450`
- [x] [`v0.2.0` GitHub Release](https://github.com/tanglijiong/LegacyPilot/releases/tag/v0.2.0) is published from the same tag and marked Latest
- [x] GitHub Release contains metrics, migration, known limitations and next direction
- [x] Local `main` and `origin/main` agree; the tag and GitHub Release both target `6675450`

The post-release mainline contains only the idempotent publication workflow and this checklist update beyond the tagged, fully verified release commit. The publication workflow completed successfully in [run 33259012876](https://github.com/tanglijiong/LegacyPilot/actions/runs/33259012876).

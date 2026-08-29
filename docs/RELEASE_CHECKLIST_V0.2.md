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
- [ ] Fresh-clone Quickstart completes within 30 minutes
- [x] Five-task real-model regression is recorded against the commit containing Issues 21–32
- [x] Model, Prompt/strategy, price and environment are recorded
- [ ] CI, Security and Eval smoke pass on the exact release commit
- [ ] No credentials, reference overlay, local absolute paths or unreviewed generated artifacts are committed

Local candidate verification ran on 2026-08-29 with OpenJDK 21.0.12.1, Maven Wrapper and Docker Engine 28.4.0 on macOS arm64. The full 24-module reactor completed in 4m24s; the Docker integration class completed in 179.8s.

## Publication

- [ ] Annotated `v0.2.0` tag points to the verified release commit
- [ ] `v0.2.0` GitHub Release is published from the same tag
- [ ] GitHub Release contains metrics, migration, known limitations and next direction
- [ ] Local `main`, `origin/main`, tag and GitHub Release target agree

# Dependency update review — 2026-08-29

Nine Dependabot pull requests were open while preparing v0.2.0. They are intentionally excluded from the release candidate because all are major-version updates or CI action major bumps, and mixing them with the already verified harness would obscure regressions.

| PR | Update | Decision for v0.2 |
| --- | --- | --- |
| [#1](https://github.com/tanglijiong/LegacyPilot/pull/1) | Spring Context 6.2 → 7.0 | Defer; coordinate with Spring Boot 4 migration |
| [#2](https://github.com/tanglijiong/LegacyPilot/pull/2) | springdoc 2.9 → 3.1 | Defer; validate with Spring Boot 4 |
| [#3](https://github.com/tanglijiong/LegacyPilot/pull/3) | Spring Boot 3.5 → 4.1 | Defer to a dedicated compatibility branch |
| [#4](https://github.com/tanglijiong/LegacyPilot/pull/4) | Spring Web 6.2 → 7.0 | Defer with the Spring Boot 4 group |
| [#5](https://github.com/tanglijiong/LegacyPilot/pull/5) | JUnit 5 → 6 | Defer; verify engine and plugin compatibility separately |
| [#6](https://github.com/tanglijiong/LegacyPilot/pull/6) | CodeQL Action 3 → 4 | Defer until after v0.2; validate runner/runtime requirements |
| [#7](https://github.com/tanglijiong/LegacyPilot/pull/7) | setup-java 4 → 6 | Defer until after v0.2; validate cache behavior |
| [#8](https://github.com/tanglijiong/LegacyPilot/pull/8) | checkout 4 → 7 | Defer until after v0.2; validate credential and submodule defaults |
| [#9](https://github.com/tanglijiong/LegacyPilot/pull/9) | upload-artifact 4 → 7 | Defer until after v0.2; validate retention and hidden-file behavior |

After v0.2, review the Spring 7/Boot 4/springdoc updates as one compatibility unit, JUnit separately, and GitHub Actions updates in a third group. Each group must pass CI, Security, Eval smoke and relevant behavior tests before merge.

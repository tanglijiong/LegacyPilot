# Security Policy

LegacyPilot executes tools against source repositories, so path, command, sandbox, approval, and secret-handling bugs are security-sensitive.

## Supported versions

Until the first stable release, only the latest tagged release and the current `main` branch receive security fixes.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability. Use GitHub's private vulnerability reporting or Security Advisory feature for this repository and include:

- affected version or commit;
- impact and realistic attack scenario;
- reproduction steps or a minimal fixture;
- any suggested mitigation.

Please avoid accessing data that is not yours, running tests against third-party systems, or publishing exploit details before a fix is available.

## Security boundaries

The project intends to enforce these defaults:

- task writes are limited to an isolated Git worktree;
- build commands run in a constrained sandbox;
- arbitrary shell strings are not exposed as Agent tools;
- network access and external side effects are denied by default;
- risky actions require a policy decision and, when configured, explicit approval;
- secrets and sensitive source content are redacted from traces and reports.

During early development, not every boundary described in the architecture is implemented. Do not run untrusted repositories until the relevant sandbox and policy issues are complete and documented as verified.

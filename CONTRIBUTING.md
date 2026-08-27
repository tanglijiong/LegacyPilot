# Contributing to LegacyPilot

Thank you for helping make enterprise coding agents safer and more verifiable.

## Before you start

- Read the [product scope](docs/PRD.md) and [architecture](docs/ARCHITECTURE.md).
- Search existing issues before opening a new one.
- For material architecture changes, open a discussion or ADR proposal first.
- Keep v0.1 focused on the end-to-end single-agent vertical slice.

## Development requirements

- JDK 21 or newer
- Git
- Docker for sandbox work introduced in later issues

The repository includes Maven Wrapper 3.9.16, so a system Maven installation is not required.

## Build and test

```bash
./mvnw verify
```

Apply the repository formatter before committing:

```bash
./mvnw spotless:apply
```

The build verifies formatting, Checkstyle, SpotBugs, unit tests, architecture rules, and JaCoCo line coverage.

## Pull requests

- Keep each pull request focused on one issue or coherent change.
- Add tests that fail without the change and pass with it.
- Update public documentation and examples when behavior changes.
- Describe security implications, especially for tools, paths, commands, sandboxes, approvals, and logging.
- Do not commit credentials, private source code, generated runtime traces, or target repositories.

## Architecture boundaries

- The domain module must remain independent of Spring, persistence, model providers, and infrastructure adapters.
- Model and tool integrations implement inward-facing ports.
- MCP endpoints must reuse the same Tool Runtime and Execution Policy as the built-in Agent.
- A model response alone must never mark a task successful; verification evidence controls the terminal state.

## Reporting bugs and requesting features

Use the GitHub issue templates. Security vulnerabilities must follow [SECURITY.md](SECURITY.md) and must not be reported in a public issue.

By contributing, you agree that your contributions are licensed under the Apache License 2.0.

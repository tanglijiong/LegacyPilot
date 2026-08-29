# Migrating from v0.1 to v0.2

## Runtime requirements

- Continue using JDK 21 or newer and the checked-in Maven Wrapper.
- Back up the configured LegacyPilot data, work and agent-state directories before the first v0.2 run.
- Do not copy a dependency cache between unrelated projects; cache identity includes Maven inputs.

## Durable state

v0.2 wraps persisted request, checkpoint, approval and report data in a versioned schema-v2 envelope. Recognized v0.1 state is migrated on load using atomic replacement while retaining the previous snapshot. Unknown future versions and damaged JSON are rejected without overwriting the source.

Run the state inspection command before resuming an older task:

```bash
java -jar apps/cli/target/legacy-pilot-cli-0.2.0.jar agent-state-check \
  --agent-state-dir .legacypilot/agent-state
```

Inspect `MIGRATION_REQUIRED`, `CORRUPTED` or `NEEDS_REVIEW` results before allowing further writes.

## Recovery and writes

- A successful journaled action is reused during recovery and is not executed again.
- An action left in an uncertain running state is not blindly replayed; the run enters `NEEDS_REVIEW`.
- Only the current lease owner/epoch may checkpoint or execute actions.
- MCP write operations require both an allowing policy decision and a matching, unexpired capability grant.
- Capability tokens are shown once; only their digest is persisted. Update automation that previously expected reusable approvals.

## Model routing

Existing single-provider configuration remains valid through the default profile. To enable fallback, define an ordered model profile with explicit attempt, Token and cost budgets. Permanent, authentication and invalid-output failures do not silently fall through as transient errors.

## Retrieval

Vector data is scoped by project revision, embedding model and file digest. Re-index after upgrading rather than copying an unversioned vector directory. When the embedding provider or vector store is unavailable, results explicitly report degraded exact/BM25/graph retrieval.

## Docker Maven execution

The trusted prewarm phase may use the network and a writable content-addressed Maven cache. The execution phase always uses `--network none` and mounts that cache read-only. Prewarm dependencies before the first offline compile/test, especially Surefire's dynamically resolved provider.

Application deployment continues to use the local process/H2 Quickstart. PostgreSQL/pgvector Docker Compose is not part of v0.2.

## Verification

After migration, run:

```bash
./mvnw clean verify
java -jar apps/cli/target/legacy-pilot-cli-0.2.0.jar eval-run
```

For production-like Maven sandbox validation, ensure Docker Desktop is running so the two real Docker integration tests execute rather than skip.

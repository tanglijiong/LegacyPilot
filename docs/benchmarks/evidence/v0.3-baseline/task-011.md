# task-011 — v0.3-baseline

- Requirement: Schedule retries with 30-second exponential backoff by attempt, capped at one hour, and reject negative attempts.
- Final result: **PASSED**
- Failure category: —
- Modified files: src/main/java/io/legacypilot/fixtures/jobs/RetryPolicy.java
- Compile result: PASS
- Test result: PASS
- Validation result: 3/3 assertions; final PASSED
- Model/tokens: `hf.co/bartowski/deepseek-coder-v2-lite-instruct-gguf:q4_k_m`; input 2225; output 164; reasoning 0; local cost $0
- Agent steps: 1; duration 6.57s
- Git diff: [task-011.diff](./task-011.diff)

## Selected context

- `pom.xml` (939 bytes)
- `src/main/java/io/legacypilot/fixtures/jobs/Job.java` (413 bytes)
- `src/main/java/io/legacypilot/fixtures/jobs/JobQueryService.java` (262 bytes)
- `src/main/java/io/legacypilot/fixtures/jobs/JobRepository.java` (162 bytes)
- `src/main/java/io/legacypilot/fixtures/jobs/JobRequestParser.java` (162 bytes)
- `src/main/java/io/legacypilot/fixtures/jobs/JobScheduler.java` (1105 bytes)
- `src/main/java/io/legacypilot/fixtures/jobs/JobStatus.java` (97 bytes)
- `src/main/java/io/legacypilot/fixtures/jobs/RetryPolicy.java` (194 bytes)
- `src/test/java/io/legacypilot/fixtures/jobs/JobSchedulerTest.java` (973 bytes)

## Agent plan

```json
{"status":"not captured by baseline adapter"}
```

## Tool calls

```json
[{"status":"not captured structurally","inferred":"model edit -> file writes -> Maven public verification -> harness hidden verification"}]
```

## Validation trace

```text
All public assertions, compilation, tests, and hidden validation passed.
```

# task-011 — v0.3-final

- Requirement: Schedule retries with 30-second exponential backoff by attempt, capped at one hour, and reject negative attempts.
- Final result: **PASSED**
- Failure category: —
- Modified files: src/main/java/io/legacypilot/fixtures/jobs/RetryPolicy.java
- Compile result: PASS
- Test result: PASS
- Validation result: 3/3 assertions; final PASSED
- Model/tokens: `hf.co/bartowski/deepseek-coder-v2-lite-instruct-gguf:q4_k_m`; input 4314; output 303; reasoning 0; local cost $0
- Agent steps: 1; duration 11.42s
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
{
  "plan": [
    "Implement a retry mechanism with exponential backoff and a maximum duration of one hour.",
    "Ensure that the retry policy respects the number of attempts and does not allow negative values."
  ],
  "risks": [
    "Incorrect implementation could lead to infinite retries or incorrect scheduling.",
    "Negative attempts could cause unexpected behavior in the job scheduling logic."
  ],
  "preserved_api": [
    "RetryPolicy.nextAttempt(Instant, int)",
    "JobScheduler.retry(Job, Instant)",
    "Job.attempt()"
  ]
}
```

## Tool calls

```json
[
  {
    "tool": "model.plan",
    "result": "completed"
  },
  {
    "tool": "model.edit",
    "attempt": 1,
    "result": "completed"
  },
  {
    "tool": "workspace.apply",
    "attempt": 1,
    "files": 1
  },
  {
    "tool": "maven.test",
    "attempt": 1,
    "result": "passed"
  }
]
```

## Validation trace

```text
All public assertions, compilation, tests, and hidden validation passed.
```

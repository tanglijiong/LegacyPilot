# task-014 — v0.3-final

- Requirement: Parse trimmed job priorities and accept only integer values from 1 through 5 with a stable INVALID_PRIORITY error.
- Final result: **FAILED**
- Failure category: Model Reasoning Failure
- Modified files: src/main/java/io/legacypilot/fixtures/jobs/JobRequestParser.java
- Compile result: FAIL
- Test result: FAIL
- Validation result: 4/4 assertions; final FAILED
- Model/tokens: `hf.co/bartowski/deepseek-coder-v2-lite-instruct-gguf:q4_k_m`; input 4420; output 655; reasoning 0; local cost $0
- Agent steps: 1; duration 17.45s
- Git diff: [task-014.diff](./task-014.diff)

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
    "Inspect callers and tests, preserve existing APIs, implement the requirement, then compile and test the complete workspace."
  ],
  "risks": [
    "Planning model output was unavailable; use conservative maintenance."
  ],
  "preserved_api": [

  ]
}
```

## Tool calls

```json
[
  {
    "tool": "model.plan",
    "result": "fallback: Expecting property name enclosed in double quotes: line 2 column 3 (char 4)"
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
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.006 s <<< FAILURE! -- in io.legacypilot.fixtures.jobs.Task014HiddenTest
[ERROR] io.legacypilot.fixtures.jobs.Task014HiddenTest.acceptsOnlyPrioritiesOneThroughFive -- Time elapsed: 0.003 s <<< FAILURE!
org.opentest4j.AssertionFailedError: Unexpected exception type thrown, expected: <java.lang.IllegalArgumentException> but was: <java.lang.NullPointerException>
at org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
at org.junit.jupiter.api.AssertThrows.assertThrows(AssertThrows.java:67)
at org.junit.jupiter.api.AssertThrows.assertThrows(AssertThrows.java:35)
at org.junit.jupiter.api.Assertions.assertThrows(Assertions.java:3128)
at io.legacypilot.fixtures.jobs.Task014HiddenTest.acceptsOnlyPrioritiesOneThroughFive(Task014HiddenTest.java:14)
at java.base/java.lang.reflect.Method.invoke(M
… (truncated; full verifier output is in checkpoint.json)
```

# task-017 — v0.3-final

- Requirement: Add a repository-backed query for READY jobs scheduled before an exclusive instant, enforcing a batch limit from 1 through 100 before querying.
- Final result: **FAILED**
- Failure category: Code/Edit Failure
- Modified files: src/main/java/io/legacypilot/fixtures/jobs/JobQueryService.java, src/main/java/io/legacypilot/fixtures/jobs/JobRepository.java
- Compile result: FAIL
- Test result: FAIL
- Validation result: 4/4 assertions; final FAILED
- Model/tokens: `hf.co/bartowski/deepseek-coder-v2-lite-instruct-gguf:q4_k_m`; input 4558; output 2060; reasoning 0; local cost $0
- Agent steps: 2; duration 37.51s
- Git diff: [task-017.diff](./task-017.diff)

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
    "files": 2
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
[ERROR] COMPILATION ERROR :
[ERROR] <repo>/evals/runs/deepseek-v03-final-20260909-r3/workspaces/task-017/src/test/java/io/legacypilot/fixtures/jobs/Task017HiddenTest.java:[18,63] 找不到符号
符号:   方法 dueBefore(java.time.Instant,int)
位置: 类型为io.legacypilot.fixtures.jobs.JobQueryService的变量 service
[ERROR] <repo>/evals/runs/deepseek-v03-final-20260909-r3/workspaces/task-017/src/test/java/io/legacypilot/fixtures/jobs/Task017HiddenTest.java:[19,63] 找不到符号
[ERROR] <repo>/evals/runs/deepseek-v03-final-20260909-r3/workspaces/task-017/src/test/java/io/legacypilot/fixtures/jobs/Task017HiddenTest.java:[21,36] 找不到符号
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.15.0:testCompile (default-testCompile) on project job-scheduler-fixture: Compilation failure: Compilation failure:
[ERROR]   符号:   方法 dueBefore(java.time.Instant,int)
[ERROR]   位置: 类型为io.legacypilot.fixtures.jobs.Job
… (truncated; full verifier output is in checkpoint.json)
```

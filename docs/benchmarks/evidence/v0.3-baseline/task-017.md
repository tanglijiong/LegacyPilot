# task-017 — v0.3-baseline

- Requirement: Add a repository-backed query for READY jobs scheduled before an exclusive instant, enforcing a batch limit from 1 through 100 before querying.
- Final result: **FAILED**
- Failure category: Code/Edit Failure
- Modified files: src/main/java/io/legacypilot/fixtures/jobs/JobQueryService.java, src/main/java/io/legacypilot/fixtures/jobs/JobRepository.java
- Compile result: FAIL
- Test result: FAIL
- Validation result: 4/4 assertions; final FAILED
- Model/tokens: `hf.co/bartowski/deepseek-coder-v2-lite-instruct-gguf:q4_k_m`; input 5682; output 748; reasoning 0; local cost $0
- Agent steps: 4; duration 21.04s
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
{"status":"not captured by baseline adapter"}
```

## Tool calls

```json
[{"status":"not captured structurally","inferred":"model edit -> file writes -> Maven public verification -> harness hidden verification"}]
```

## Validation trace

```text
[ERROR] COMPILATION ERROR :
[ERROR] <repo>/evals/runs/deepseek-v03-baseline-20260909/workspaces/task-017/src/test/java/io/legacypilot/fixtures/jobs/Task017HiddenTest.java:[18,63] 找不到符号
符号:   方法 dueBefore(java.time.Instant,int)
位置: 类型为io.legacypilot.fixtures.jobs.JobQueryService的变量 service
[ERROR] <repo>/evals/runs/deepseek-v03-baseline-20260909/workspaces/task-017/src/test/java/io/legacypilot/fixtures/jobs/Task017HiddenTest.java:[19,63] 找不到符号
[ERROR] <repo>/evals/runs/deepseek-v03-baseline-20260909/workspaces/task-017/src/test/java/io/legacypilot/fixtures/jobs/Task017HiddenTest.java:[21,36] 找不到符号
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.15.0:testCompile (default-testCompile) on project job-scheduler-fixture: Compilation failure: Compilation failure:
[ERROR]   符号:   方法 dueBefore(java.time.Instant,int)
[ERROR]   位置: 类型为io.legacypilot.fixtures.jobs.Job
… (truncated; full verifier output is in checkpoint.json)
```

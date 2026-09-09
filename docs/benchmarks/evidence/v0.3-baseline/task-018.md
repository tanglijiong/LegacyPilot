# task-018 — v0.3-baseline

- Requirement: Add balance-backed transfers that validate the amount, reject insufficient funds before writes, persist the debited account, and then record the transfer.
- Final result: **FAILED**
- Failure category: Code/Edit Failure
- Modified files: src/main/java/io/legacypilot/samples/banking/Account.java, src/main/java/io/legacypilot/samples/banking/TransferService.java
- Compile result: FAIL
- Test result: FAIL
- Validation result: 3/4 assertions; final FAILED
- Model/tokens: `hf.co/bartowski/deepseek-coder-v2-lite-instruct-gguf:q4_k_m`; input 8113; output 1238; reasoning 0; local cost $0
- Agent steps: 4; duration 31.24s
- Git diff: [task-018.diff](./task-018.diff)

## Selected context

- `pom.xml` (1500 bytes)
- `src/main/java/io/legacypilot/samples/banking/Account.java` (161 bytes)
- `src/main/java/io/legacypilot/samples/banking/AccountRepository.java` (249 bytes)
- `src/main/java/io/legacypilot/samples/banking/Customer.java` (104 bytes)
- `src/main/java/io/legacypilot/samples/banking/CustomerRepository.java` (221 bytes)
- `src/main/java/io/legacypilot/samples/banking/Transaction.java` (212 bytes)
- `src/main/java/io/legacypilot/samples/banking/TransferController.java` (1299 bytes)
- `src/main/java/io/legacypilot/samples/banking/TransferRecord.java` (197 bytes)
- `src/main/java/io/legacypilot/samples/banking/TransferRepository.java` (361 bytes)
- `src/main/java/io/legacypilot/samples/banking/TransferService.java` (1353 bytes)
- `src/main/resources/db/migration/V1__banking_baseline.sql` (733 bytes)
- `src/test/java/io/legacypilot/samples/banking/TransferServiceTest.java` (2093 bytes)

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
src/main/java/io/legacypilot/samples/banking/TransferService.java: CONTAINS failed; [ERROR] COMPILATION ERROR :
[ERROR] <repo>/evals/runs/deepseek-v03-baseline-20260909/workspaces/task-018/src/main/java/io/legacypilot/samples/banking/Account.java:[12,9] 无法为 final 变量 balance 分配值
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.15.0:compile (default-compile) on project banking-demo: Compilation failure
[ERROR] -> [Help 1]
[ERROR]
[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
[ERROR] Re-run Maven using the -X switch to enable full debug logging.
[ERROR] For more information about the errors and possible solutions, please read the following articles:
[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
```

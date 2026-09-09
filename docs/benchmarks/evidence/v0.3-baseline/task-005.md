# task-005 — v0.3-baseline

- Requirement: Enforce atomic UTC daily transfer limits of 50000 for standard customers and 200000 for VIP customers, including concurrent transfers and day rollover.
- Final result: **FAILED**
- Failure category: Code/Edit Failure
- Modified files: src/main/java/io/legacypilot/samples/banking/DailyTransferPolicy.java, src/main/java/io/legacypilot/samples/banking/TransferLimitException.java, src/main/java/io/legacypilot/samples/banking/TransferService.java
- Compile result: PASS
- Test result: PASS
- Validation result: 4/5 assertions; final FAILED
- Model/tokens: `hf.co/bartowski/deepseek-coder-v2-lite-instruct-gguf:q4_k_m`; input 7304; output 1030; reasoning 0; local cost $0
- Agent steps: 3; duration 22.36s
- Git diff: [task-005.diff](./task-005.diff)

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
src/main/java/io/legacypilot/samples/banking/TransferService.java: CONTAINS failed
```

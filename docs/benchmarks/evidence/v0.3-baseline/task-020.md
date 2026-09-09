# task-020 — v0.3-baseline

- Requirement: Add HMAC-SHA256 webhook verification for order payloads using hexadecimal signatures, constant-time comparison, and safe rejection of malformed input.
- Final result: **FAILED**
- Failure category: Code/Edit Failure
- Modified files: src/main/java/io/legacypilot/fixtures/orders/OrderWebhookVerifier.java
- Compile result: FAIL
- Test result: FAIL
- Validation result: 4/4 assertions; final FAILED
- Model/tokens: `hf.co/bartowski/deepseek-coder-v2-lite-instruct-gguf:q4_k_m`; input 2265; output 503; reasoning 0; local cost $0
- Agent steps: 1; duration 11.50s
- Git diff: [task-020.diff](./task-020.diff)

## Selected context

- `pom.xml` (939 bytes)
- `src/main/java/io/legacypilot/fixtures/orders/ApiResponse.java` (307 bytes)
- `src/main/java/io/legacypilot/fixtures/orders/Order.java` (235 bytes)
- `src/main/java/io/legacypilot/fixtures/orders/OrderAuditFormatter.java` (202 bytes)
- `src/main/java/io/legacypilot/fixtures/orders/OrderController.java` (287 bytes)
- `src/main/java/io/legacypilot/fixtures/orders/OrderRepository.java` (172 bytes)
- `src/main/java/io/legacypilot/fixtures/orders/OrderService.java` (921 bytes)
- `src/main/java/io/legacypilot/fixtures/orders/OrderStatus.java` (105 bytes)
- `src/test/java/io/legacypilot/fixtures/orders/OrderServiceTest.java` (1094 bytes)

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
[ERROR] <repo>/evals/runs/deepseek-v03-baseline-20260909/workspaces/task-020/src/test/java/io/legacypilot/fixtures/orders/Task020HiddenTest.java:[22,24] 找不到符号
符号:   方法 verify(byte[],java.lang.String,byte[])
位置: 类型为io.legacypilot.fixtures.orders.OrderWebhookVerifier的变量 verifier
[ERROR] <repo>/evals/runs/deepseek-v03-baseline-20260909/workspaces/task-020/src/test/java/io/legacypilot/fixtures/orders/Task020HiddenTest.java:[23,25] 找不到符号
[ERROR] <repo>/evals/runs/deepseek-v03-baseline-20260909/workspaces/task-020/src/test/java/io/legacypilot/fixtures/orders/Task020HiddenTest.java:[24,25] 找不到符号
[ERROR] <repo>/evals/runs/deepseek-v03-baseline-20260909/workspaces/task-020/src/test/java/io/legacypilot/fixtures/orders/Task020HiddenTest.java:[25,25] 找不到符号
符号:   方法 verify(<nulltype>,java.lang.String,byte[])
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-com
… (truncated; full verifier output is in checkpoint.json)
```

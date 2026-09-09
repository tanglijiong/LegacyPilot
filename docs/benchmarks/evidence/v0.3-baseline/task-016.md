# task-016 — v0.3-baseline

- Requirement: Add order cancellation so CREATED orders become CANCELLED, repeated cancellation is idempotent without another write, and COMPLETED orders are rejected before writing.
- Final result: **FAILED**
- Failure category: Code/Edit Failure
- Modified files: src/main/java/io/legacypilot/fixtures/orders/Order.java, src/main/java/io/legacypilot/fixtures/orders/OrderService.java
- Compile result: FAIL
- Test result: FAIL
- Validation result: 1/4 assertions; final FAILED
- Model/tokens: `hf.co/bartowski/deepseek-coder-v2-lite-instruct-gguf:q4_k_m`; input 7496; output 1556; reasoning 0; local cost $0
- Agent steps: 4; duration 36.97s
- Git diff: [task-016.diff](./task-016.diff)

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
src/main/java/io/legacypilot/fixtures/orders/Order.java: CONTAINS failed; src/main/java/io/legacypilot/fixtures/orders/OrderService.java: CONTAINS failed; src/main/java/io/legacypilot/fixtures/orders/OrderService.java: MATCHES_REGEX failed; [ERROR] COMPILATION ERROR :
[ERROR] <repo>/evals/runs/deepseek-v03-baseline-20260909/workspaces/task-016/src/main/java/io/legacypilot/fixtures/orders/Order.java:[3,8] 枚举 OrderStatus 是公共的, 应在名为 OrderStatus.java 的文件中声明
[ERROR] <repo>/evals/runs/deepseek-v03-baseline-20260909/workspaces/task-016/src/main/java/io/legacypilot/fixtures/orders/OrderStatus.java:[3,8] 类重复: io.legacypilot.fixtures.orders.OrderStatus
[ERROR] <repo>/evals/runs/deepseek-v03-baseline-20260909/workspaces/task-016/src/main/java/io/legacypilot/fixtures/orders/Order.java:[17,17] 找不到符号
符号:   类 Instant
位置: 类 io.legacypilot.fixtures.orders.Order
[ERROR] <repo>/evals/runs/deepseek-v03-base
… (truncated; full verifier output is in checkpoint.json)
```

# task-020 — v0.3-final

- Requirement: Add HMAC-SHA256 webhook verification for order payloads using hexadecimal signatures, constant-time comparison, and safe rejection of malformed input.
- Final result: **FAILED**
- Failure category: Code/Edit Failure
- Modified files: src/main/java/io/legacypilot/fixtures/orders/OrderWebhookVerifier.java
- Compile result: FAIL
- Test result: FAIL
- Validation result: 3/4 assertions; final FAILED
- Model/tokens: `hf.co/bartowski/deepseek-coder-v2-lite-instruct-gguf:q4_k_m`; input 10813; output 1609; reasoning 0; local cost $0
- Agent steps: 3; duration 35.44s
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
{
  "plan": [
    "Add a new method to OrderWebhookVerifier to verify the HMAC-SHA256 signature of the order payload using hexadecimal signatures and constant-time comparison.",
    "Ensure safe rejection of malformed input by validating the input format and size before proceeding with the signature verification."
  ],
  "risks": [
    "Potential risk of introducing bugs in the signature verification logic.",
    "Risk of not properly handling malformed input, leading to security vulnerabilities."
  ],
  "preserved_api": [
    "OrderWebhookVerifier class",
    "OrderService class",
    "OrderRepository interface",
    "ApiResponse record",
    "Order record",
    "OrderAuditFormatter class",
    "OrderController class",
    "OrderStatus enum",
    "MemoryRepository class in OrderServiceTest"
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
    "result": "failed"
  },
  {
    "tool": "model.edit",
    "attempt": 2,
    "result": "completed"
  },
  {
    "tool": "workspace.apply",
    "attempt": 2,
    "files": 1
  },
  {
    "tool": "maven.test",
    "attempt": 2,
    "result": "failed"
  },
  {
    "tool": "model.edit",
    "attempt": 3,
    "result": "completed"
  },
  {
    "tool": "workspace.apply",
    "attempt": 3,
    "files": 1
  },
  {
    "tool": "maven.test",
    "attempt": 3,
    "result": "failed"
  }
]
```

## Validation trace

```text
src/main/java/io/legacypilot/fixtures/orders/OrderWebhookVerifier.java: CONTAINS failed; [ERROR] COMPILATION ERROR :
[ERROR] <repo>/evals/runs/deepseek-v03-final-20260909-r3/workspaces/task-020/src/test/java/io/legacypilot/fixtures/orders/Task020HiddenTest.java:[22,24] 找不到符号
符号:   方法 verify(byte[],java.lang.String,byte[])
位置: 类型为io.legacypilot.fixtures.orders.OrderWebhookVerifier的变量 verifier
[ERROR] <repo>/evals/runs/deepseek-v03-final-20260909-r3/workspaces/task-020/src/test/java/io/legacypilot/fixtures/orders/Task020HiddenTest.java:[23,25] 找不到符号
[ERROR] <repo>/evals/runs/deepseek-v03-final-20260909-r3/workspaces/task-020/src/test/java/io/legacypilot/fixtures/orders/Task020HiddenTest.java:[24,25] 找不到符号
[ERROR] <repo>/evals/runs/deepseek-v03-final-20260909-r3/workspaces/task-020/src/test/java/io/legacypilot/fixtures/orders/Task020HiddenTest.java:[25,25] 找不到符号
符号:   方法 verify(<nulltype>,j
… (truncated; full verifier output is in checkpoint.json)
```

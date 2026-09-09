# task-006 — v0.3-baseline

- Requirement: Make order creation idempotent by requestId so repeated requests return the original order without another repository write.
- Final result: **FAILED**
- Failure category: Model Reasoning Failure
- Modified files: src/main/java/io/legacypilot/fixtures/orders/OrderRepository.java, src/main/java/io/legacypilot/fixtures/orders/OrderService.java
- Compile result: FAIL
- Test result: FAIL
- Validation result: 2/3 assertions; final FAILED
- Model/tokens: `hf.co/bartowski/deepseek-coder-v2-lite-instruct-gguf:q4_k_m`; input 4724; output 950; reasoning 0; local cost $0
- Agent steps: 4; duration 21.31s
- Git diff: [task-006.diff](./task-006.diff)

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
src/main/java/io/legacypilot/fixtures/orders/OrderService.java: MATCHES_REGEX failed; [ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.053 s <<< FAILURE! -- in io.legacypilot.fixtures.orders.Task006HiddenTest
[ERROR] io.legacypilot.fixtures.orders.Task006HiddenTest.repeatedRequestReturnsOriginalOrderWithoutAnotherWrite -- Time elapsed: 0.040 s <<< FAILURE!
org.opentest4j.AssertionFailedError: expected: <Order[id=c7052091-9def-439c-a581-9ec99690eac1, requestId=request-7, sku=sku-1, quantity=2, status=CREATED, paymentToken=token, createdAt=2026-09-09T17:26:52.053769Z]> but was: <Order[id=2a645622-2ba1-4bee-ad3e-b1eb8874a1b6, requestId=request-7, sku=sku-1, quantity=2, status=CREATED, paymentToken=token, createdAt=2026-09-09T17:26:52.053803Z]>
at org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
at org.junit.jupiter.api.AssertionF
… (truncated; full verifier output is in checkpoint.json)
```

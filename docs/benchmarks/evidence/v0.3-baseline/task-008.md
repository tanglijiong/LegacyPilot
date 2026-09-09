# task-008 — v0.3-baseline

- Requirement: Map missing orders to an HTTP-style 404 response with the stable error code ORDER_NOT_FOUND.
- Final result: **FAILED**
- Failure category: Model Reasoning Failure
- Modified files: src/main/java/io/legacypilot/fixtures/orders/OrderController.java, src/main/java/io/legacypilot/fixtures/orders/OrderNotFoundException.java, src/main/java/io/legacypilot/fixtures/orders/OrderService.java
- Compile result: FAIL
- Test result: FAIL
- Validation result: 3/3 assertions; final FAILED
- Model/tokens: `hf.co/bartowski/deepseek-coder-v2-lite-instruct-gguf:q4_k_m`; input 2260; output 575; reasoning 0; local cost $0
- Agent steps: 3; duration 12.56s
- Git diff: [task-008.diff](./task-008.diff)

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
[ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 0.024 s <<< FAILURE! -- in io.legacypilot.fixtures.orders.Task008HiddenTest
[ERROR] io.legacypilot.fixtures.orders.Task008HiddenTest.missingOrderMapsToStableNotFoundResponse -- Time elapsed: 0.014 s <<< ERROR!
io.legacypilot.fixtures.orders.OrderNotFoundException: Order not found
at io.legacypilot.fixtures.orders.OrderService.lambda$get$0(OrderService.java:30)
at java.base/java.util.Optional.orElseThrow(Optional.java:403)
at io.legacypilot.fixtures.orders.OrderService.get(OrderService.java:30)
at io.legacypilot.fixtures.orders.OrderController.get(OrderController.java:12)
at io.legacypilot.fixtures.orders.Task008HiddenTest.missingOrderMapsToStableNotFoundResponse(Task008HiddenTest.java:21)
at java.base/java.lang.reflect.Method.invoke(Method.java:580)
at java.base/java.util.ArrayList.forEach(ArrayList.java:1596)
[ERROR
… (truncated; full verifier output is in checkpoint.json)
```

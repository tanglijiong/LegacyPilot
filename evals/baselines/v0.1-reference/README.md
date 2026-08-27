# v0.1 Reference Ceiling

- Dataset: `v0.1`
- Fixture: `banking-fixture-v2`
- Executor: `reference-ceiling`（不是 LLM）
- Java: 21
- Prompt: `planner-v1`
- Policy: `policy-v1`
- Estimated model cost: `$0`
- Result: **5/5 passed**

所有任务均在独立临时 workspace 中应用隐藏参考覆盖层，并实际执行 fixture `mvn test`。task-005 的测试覆盖普通客户、VIP、UTC 跨日、超限拒绝以及并发检查/写入原子性。

真实模型五任务基线仍待配置 provider credentials 后执行；不得把本报告作为模型能力分数。

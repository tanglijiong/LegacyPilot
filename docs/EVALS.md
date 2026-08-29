# Eval Harness 与公开基线

## 单命令运行

```bash
./mvnw -q -pl apps/cli -am package
java -jar apps/cli/target/legacy-pilot-cli-0.2.0.jar eval-run
```

也可在开发环境直接运行 `EvalRunCommand`，通过 `--dataset`、`--fixture`、`--references`、`--maven-wrapper` 和 `--concurrency` 指定路径与并发数。

## Dataset v0.1

固定 fixture revision：`banking-fixture-v2`。

| Task | 类别 | 确定性目标 |
| --- | --- | --- |
| task-001 | enum | AccountStatus 类型安全枚举 |
| task-002 | validation | 非正数转账在写库前拒绝 |
| task-003 | defect-fix | 非法历史查询时间范围拒绝 |
| task-004 | query-field | TransferQuery 聚合字段 |
| task-005 | transfer-limit | 普通/VIP、UTC 跨日和并发原子限额 |

每个任务包含 `task.yml`、`fixture.ref` 和 `assertions.yml`。Runner 为每个任务复制独立 workspace，并发执行后汇总断言、编译、测试、Recall、步骤、Token、费用、时长和产物路径。

## 基线解释

- `reference-ceiling` 使用不可见于 fixture 索引的参考覆盖层，验证 dataset 与断言本身可达到 5/5；它不是模型成绩。
- `fake-replay` 端到端测试从 baseline 重新建立索引，经过 Planner、审批、4 次受控 patch、Verification Pipeline 和真实 Maven 测试完成 task-005。
- 真实模型基线已使用 OpenAI Codex 的现有 ChatGPT 账户凭据运行：GPT-5.4 首次 1/5，在每任务最多一次公开断言反馈重试后达到 5/5。模型、Prompt/策略版本、价格口径、Token 和环境证据见 [`evals/baselines/2026-08-29-gpt-5.4-codex-agent-v1`](../evals/baselines/2026-08-29-gpt-5.4-codex-agent-v1/README.md)。报告明确区分 reference ceiling、首次模型成绩和带重试策略的最终成绩。

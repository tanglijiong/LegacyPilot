# Eval Harness 与公开基线

## 单命令运行

```bash
./mvnw -q -pl apps/cli -am package
java -jar apps/cli/target/legacy-pilot-cli-0.1.0-SNAPSHOT.jar eval-run
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
- 尚未提交真实外部模型的五任务分数，因为当前环境没有提供模型凭据。发布报告不会把 reference ceiling 冒充模型成功率。

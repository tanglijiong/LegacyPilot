# LegacyPilot 文档导航

本目录是 LegacyPilot 的规划基线。发生范围冲突时，优先级依次为：PRD 的 MVP 边界、架构决策、路线图、Backlog。

| 文档 | 解决的问题 |
| --- | --- |
| [PRD](PRD.md) | 为谁解决什么问题、MVP 做到什么程度、如何验收 |
| [技术架构](ARCHITECTURE.md) | Agent 如何运行、组件如何协作、安全与验证如何落地 |
| [仓库结构](REPOSITORY_STRUCTURE.md) | 代码和资源放在哪里、模块边界是什么 |
| [路线图](ROADMAP.md) | v0.1 到 v0.4 的阶段目标与退出条件 |
| [MVP Backlog](MVP_BACKLOG.md) | 可以直接创建为 GitHub Issues 的 20 项工作 |
| [架构决策](DECISIONS.md) | 已确定的关键取舍及其原因 |
| [长期任务：基础闭环](NEXT_TASK.md) | 已完成 Issue 02–04 的执行说明与验收结果 |
| [长期任务：安全工具运行时](NEXT_TASK_ISSUES_05_10_11.md) | 已完成 Issue 05、10、11 的安全设计、实现与验收结果 |
| [长期任务：Java 代码智能](NEXT_TASK_ISSUES_06_09.md) | 已完成 Issue 06–09 的索引、依赖图、混合检索与上下文验收结果 |
| [长期任务：Agent 执行闭环](NEXT_TASK_ISSUES_12_16.md) | 已完成 Issue 12–16 的模型网关、Agent Loop、审批、验证和可观测性验收结果 |
| [MCP、Demo 与 Eval](NEXT_TASK_ISSUES_17_20.md) | Issue 17–20 工程验收结果与仍需人工完成的发布动作 |
| [可恢复长任务 Harness](NEXT_TASK_ISSUES_21_26.md) | 已完成 Issues 21–26 的版本化状态、Journal、Lease、持久 Trace、Memory 与故障验收 |
| [受治理工具链与模型韧性](NEXT_TASK_ISSUES_27_32.md) | 已完成 Issues 27–32 的规划、实现与验收结果 |
| [恢复机制运维指南](RESILIENT_HARNESS.md) | 版本化状态、Journal、Lease、Trace、Memory 与故障恢复 |
| [受治理 Harness 指南](GOVERNED_HARNESS.md) | Policy、Capability、MCP 写工具、模型路由、Vector/Reranker 和 Docker 依赖治理 |
| [Quickstart](QUICKSTART.md) | 在新环境构建、运行 Eval、启动 MCP 和排查常见故障 |
| [MCP Server](MCP_SERVER.md) | STDIO 配置、工具清单和安全边界 |
| [Eval Harness](EVALS.md) | 五任务数据集、指标和基线解释 |

## 版本定义

- **v0.1 Vertical Slice**：一条需求从输入到验证报告完整跑通。
- **v0.2 Strong Harness**：强化 MCP、上下文压缩、记忆、审批与恢复能力。
- **v0.3 AgentOps**：形成 Eval、指标、成本和 Dashboard 闭环。
- **v0.4 Modernization**：支持 Java 升级，并探索 COBOL 业务规则提取。

## 文档维护规则

1. 新功能必须能映射到 PRD 中的用户价值或成功指标。
2. 改变模块边界、安全模型或状态机时，先新增或更新 ADR。
3. Issue 完成后同步更新路线图状态，避免文档与实现脱节。
4. v0.1 期间优先保证端到端闭环，不用“未来扩展性”制造无必要抽象。

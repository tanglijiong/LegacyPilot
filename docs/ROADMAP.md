# LegacyPilot 路线图

路线图按“可证明的用户价值”推进，不按组件数量推进。日期在首次估算和维护者时间确定后填写；当前以版本退出条件为准。

## 总览

| 版本 | 主题 | 核心证明 |
| --- | --- | --- |
| v0.1 | Vertical Slice | Agent 能在受控环境完成一条 Java 需求并给出验证报告 |
| v0.2 | Strong Harness | 长任务具备更强的上下文、审批、恢复和 MCP 复用能力 |
| v0.3 | AgentOps | Agent 的质量、成本和运行过程可以持续评测与观察 |
| v0.4 | Legacy Modernization | 能处理 Java 升级，并验证遗留业务规则提取思路 |

## v0.1 — Vertical Slice

### 用户可见目标

开发者可以对示例 Spring Boot Banking 项目提交一条业务需求，查看影响分析和计划，批准后让 Agent 修改代码，并获得 Maven 编译、测试、静态分析与风险报告。

### 交付范围

- Maven 多模块工程、CI、代码质量与安全基线。
- Project/Task/Run 领域模型、状态机和预算控制。
- Git worktree 管理、路径边界和 Docker 执行基线。
- JavaParser AST、符号、Spring 角色和基础依赖图。
- BM25 + 精确符号 + 图扩展检索；向量检索可插拔。
- Model Gateway 和至少一个 Spring AI 模型适配器。
- 受控 File/Git/Maven/Patch 工具。
- 计划审批、执行循环、失败修复和终止条件。
- compile/test/static analysis 验证与 Markdown/JSON 报告。
- CLI/REST 最小交互面。
- 最小只读/验证型 MCP Server。
- Banking Demo、5 个 Eval 任务和公开基线。

### 退出条件

- 5 个基准任务至少 4 个成功，运行环境和模型配置可复现。
- 100% 写入位于任务 worktree，安全边界测试全部通过。
- 所有 Tool Invocation 有结构化 Trace 和终态。
- 演示需求能从空索引开始跑到验证报告。
- 新用户按 README 可在 30 分钟内启动并运行 smoke task。
- 发布 `v0.1.0` tag、示例报告和演示 GIF/视频。

## v0.2 — Strong Harness

**当前进度：** 第一阶段 Issues 21–26 已完成；第二阶段 Issues 27–32 已规划，详见 [`NEXT_TASK_ISSUES_27_32.md`](NEXT_TASK_ISSUES_27_32.md)。

### 目标

把 v0.1 的可运行闭环变成可恢复、可治理、可被其他客户端复用的 Harness。

### 候选范围

- Context Compaction、摘要版本与长期任务上下文治理。
- Session/Task Memory，带来源、TTL 和可删除性。
- 更细粒度的 Policy DSL 与审批规则。
- Checkpoint 恢复、幂等执行 ID、人工接管与继续。
- MCP 工具集扩展：Git、Java Project、Maven；写工具保持显式授权。
- 多模型策略与 fallback，但不做多 Agent。
- 向量存储与 Reranker 的正式基准对比。
- 更严格的 Docker 网络与依赖缓存策略。

### 退出条件

- 服务中断后能恢复至少一个处于执行中的示例任务。
- 长任务上下文始终保持在预算内，且 Trace 可解释裁剪内容。
- MCP 客户端可完成“搜索 → 引用 → 编译/测试”的受控流程。
- 安全测试覆盖审批绕过、路径逃逸、命令注入和敏感信息泄露。

## v0.3 — AgentOps

### 目标

让每次策略、模型和检索变更都有量化证据。

### 候选范围

- Eval 数据集扩展到至少 30 个任务。
- 检索 Recall、计划准确率、编译成功率、测试通过率等分层指标。
- 模型/Prompt/策略对比实验和回归阈值。
- Token、成本、时长、Tool failure、Retry 等指标聚合。
- OpenTelemetry Trace 导出和 React Dashboard。
- 失败任务分类、回放、人工标注和反馈闭环。
- CI 中的确定性 smoke eval；完整 LLM eval 采用按需/定时执行。

### 退出条件

- 每个版本公开固定数据集上的基线报告。
- Dashboard 能从 Task 下钻到 Step、Tool 和 Verification evidence。
- 策略变更若导致关键指标超过阈值退化，CI/发布流程明确告警。
- Eval 结果区分模型波动、工具故障与产品缺陷。

## v0.4 — Legacy Modernization

### 目标

利用既有代码理解与验证底座，进入真正的遗留系统现代化场景。

### 第一优先：Java Modernization

- Java 8/11 到 Java 21 的兼容性分析。
- Spring Boot 升级影响清单和分阶段计划。
- 依赖升级、废弃 API 检测、自动补丁和回归验证。
- 行为特征测试与升级前后对比。

### 探索项：COBOL Business Rule Extraction

- 解析程序结构、数据定义和控制流。
- 提取带来源位置的业务规则和决策表。
- 生成规范、测试草案和 Java skeleton。
- 通过 golden cases 做行为对比。

COBOL 到 Java 的全自动生产级转换不作为 v0.4 承诺。先证明业务规则提取和行为验证的可信度。

### 退出条件

- 至少一个公开 Java legacy fixture 完成可复现升级。
- 升级报告包含 breaking changes、补丁、测试与残留人工项。
- COBOL 探索结果有明确准确率和人工复核说明，不用 Demo 代替质量证据。

## 跨版本工作流

### 每个版本必须包含

- Threat model 和安全回归。
- 文档、示例配置和迁移说明。
- 固定 Eval 数据集与基线结果。
- 可复现的演示脚本。
- 已知限制与非目标。

### 暂不承诺

- 云端 SaaS、多租户和计费。
- 自动提交、推送、开 PR 或合并生产分支。
- Kubernetes 和分布式多 Agent。
- 模型训练平台。

这些能力只有在真实用户需求和核心成功指标稳定后重新评估。

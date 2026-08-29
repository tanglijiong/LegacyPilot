# 下一长期任务：受治理工具链与模型韧性

**覆盖 Issues：** 27–32

**版本目标：** v0.2 Strong Harness 第二阶段

**状态：** Engineering Complete

**规划日期：** 2026-08-28

**完成日期：** 2026-08-28

## 启动时缺口与后续完成状态

### A. 前置基线

Issues 21–26 的实现、文档和本地验收已提交为 `ecbcfcb`，形成了本阶段可回退的稳定基线。

### B. v0.1 外部发布动作（已完成）

以下事项在本阶段启动时依赖本地之外的条件，不能用确定性 fixture 结果代替：

1. 使用真实 provider credentials 运行五任务模型基线，目标至少 4/5，并记录模型、Prompt/策略版本、价格表和环境。
2. 在全新环境按 Quickstart 完成 30 分钟 smoke task。
3. 在可用 Docker daemon 上执行 Docker 集成测试。
4. 确认远端 GitHub Actions 的 CI、Security 和 Eval smoke 工作流通过。
5. 录制不使用 reference overlay 的公开演示 GIF/视频。
6. 审阅发布证据后创建并推送 `v0.1.0` tag。

以上六项现均已完成，最终证据见 [`RELEASE_CHECKLIST_V0.1.md`](RELEASE_CHECKLIST_V0.1.md)。该列表保留为历史审计记录，不再表示当前待办。

### C. 实施前 v0.2 工程缺口（Issues 27–32 已完成）

- `DefaultExecutionPolicy` 仍按风险等级硬编码，缺少版本化规则、规则解释、冲突优先级和回归矩阵。
- 审批能够绑定 plan/action digest，但还没有面向外部客户端的短期、最小权限 capability grant。
- MCP 只开放搜索、读取、Git diff 和 Maven 验证；写工具尚未接入显式授权、Journal 和恢复语义。
- Model Gateway 只有单 provider 调用链，没有按阶段/成本路由、有限 fallback 和熔断证据。
- Vector Retriever 只有可插拔空壳，尚无正式存储实现、Reranker 和对比基线。
- Docker 默认无网络且缓存只读，但缺少依赖预热、内容寻址缓存、镜像 digest 策略和完整的有 Docker 验收证据。

## 完成结果

| Issue | 已交付能力 | 验收证据 |
| --- | --- | --- |
| 27 | 版本化 YAML/JSON Policy、确定性冲突优先级、path/tool/risk/idempotency 匹配、secure-default reload、rule/revision 解释 | 默认行为兼容，50 组确定性安全矩阵、损坏/未来版本保留上一策略测试通过 |
| 28 | Opaque Capability、磁盘仅存 digest、session/run/tool/workspace/action/plan 绑定、原子消费、撤销/过期、CLI/REST | 50 路并发一次性 token 只有一个成功消费者；错 scope、撤销、过期和重放全部拒绝 |
| 29 | MCP `project.apply_patch`、Capability + Policy + Journal + Lease、结构化错误、效果核对与 `NEEDS_REVIEW` | 无授权不写入；合法授权只写一次；已消费 token 重放拒绝；workspace 效果变化后进入人工审查 |
| 30 | Model Profile 路由、共享尝试/token/费用预算、有限 fallback、provider circuit breaker、route events | transient/permanent/invalid/budget/circuit-open/recovery Fake provider 矩阵通过 |
| 31 | Embedding/Vector/Reranker 端口、本地版本化 Vector Store、revision/model/file digest 隔离、显式 degraded | stale replacement、revision 删除、provider 降级、lexical/vector/hybrid/rerank Recall@K 与 MRR 对比通过 |
| 32 | 网络预热与离线执行分离、内容寻址 Maven cache、digest image policy、缓存链接/容量边界、日志脱敏、综合 MCP/模型/检索 E2E | 无 Docker 合同测试通过；真实预热/离线 Banking 测试在 daemon/image 不可用时明确 skip |

全仓 `clean verify` 已通过：126 个测试，0 failure、0 error、2 skipped；两个跳过项都是本机 Docker daemon 不可用时的真实 Docker 集成测试。聚合 JaCoCo 指令覆盖率 88.0%、分支覆盖率 66.9%、行覆盖率 88.3%。Spotless、编译、测试、JaCoCo、SpotBugs 和依赖规则均在同一轮构建中通过。

### D. 后续版本，不纳入本阶段

- v0.3：30+ Eval 数据集、OpenTelemetry、指标聚合、React Dashboard、人工标注反馈闭环。
- v0.4：Java 8/11 升级和 COBOL 业务规则提取探索。

## 长期任务目标

让 LegacyPilot 在开放受控写能力和多 provider 能力时仍然保持 fail-closed：每个工具决定都能解释到规则版本，每个外部写调用都需要范围明确且可消费的授权，每次模型 fallback、检索变化和沙箱依赖获取都有可复现证据。

## Issue 27 — 版本化 Policy DSL 与规则解释

### 工作内容

- 定义版本化 YAML/JSON Policy 文档和严格 schema。
- 支持按 tool、risk、idempotency、workspace/path 范围和运行模式匹配。
- 定义确定性优先级：显式 `DENY` 高于审批要求，审批要求高于 `ALLOW`；同级规则按 specificity 和稳定顺序解析。
- `PolicyDecision` 增加 rule ID、policy revision、原因和所需授权范围。
- 配置缺失、未知版本、语法错误和规则冲突时 fail-closed，并保留上一份已验证策略。
- 建立默认策略等价性测试和表驱动安全回归矩阵。

### 验收条件

- 默认策略对现有工具的决策与当前行为兼容。
- 至少 50 组 allow/approval/deny/冲突/损坏配置用例确定性通过。
- Trace、报告和 CLI/REST 能展示命中的 rule ID 与 policy revision，且不泄露敏感输入。

## Issue 28 — Capability Grant 与原子审批消费

### 工作内容

- 定义短期 capability grant：subject、session/run、tool、workspace、action/plan digest、过期时间、最大使用次数。
- 只向调用方返回高熵 opaque token，磁盘仅保存 token digest 和脱敏元数据。
- 实现签发、校验、原子消费、撤销、过期清理和重放拒绝。
- 将现有 `ONCE`/`PLAN` 审批映射为 capability，而不降低 Agent Runtime 的 digest 绑定。
- 为 CLI/REST 增加签发、查看状态和撤销入口；所有动作写入持久 Trace。

### 验收条件

- 并发消费一次性 grant 时只有一个调用成功。
- 跨 session、跨 workspace、错 tool、错 digest、过期和撤销 token 全部被拒绝。
- 状态文件、Trace、错误消息和报告均不包含明文 token。

## Issue 29 — 显式授权的 MCP 写工具

### 工作内容

- 在 MCP 中接入 `apply_patch` 等最小写工具集；默认仍保持只读/验证能力可用。
- 写调用必须同时通过 Policy DSL 和 capability 校验，不接受仅由模型参数声明“已审批”。
- 复用 Action Journal、稳定 action ID、lease/fencing 和 `NEEDS_REVIEW` 恢复语义。
- 对每个会话固定 workspace，继续阻止绝对路径、父目录逃逸、符号链接和硬链接越界。
- 增加 MCP initialize/list/call 的授权发现、结构化错误和审计 evidence。

### 验收条件

- 无 token、错 scope、已消费或过期 token 的写调用不产生磁盘变化。
- 合法一次性授权只允许一次目标补丁，重放不会重复写入。
- MCP 端到端流程完成“搜索 → 读取 → 获取授权 → patch → diff → Maven test”，并生成完整 Journal/Trace。

## Issue 30 — 多模型路由、有限 Fallback 与熔断

### 工作内容

- 定义 model profile：provider/model、适用阶段、优先级、token/费用上限和 fallback 链。
- 仅对明确可重试的限流、超时和 provider 暂时不可用执行有限 fallback。
- 结构化输出错误、内容安全拒绝、预算耗尽和策略拒绝不得通过换模型静默绕过。
- 增加 per-provider circuit breaker、冷却时间和可测试 Clock。
- 聚合每次尝试的 token、估算费用、延迟、错误分类和最终选择，并写入 Trace/Eval。

### 验收条件

- Fake provider 矩阵覆盖成功、限流、超时、永久错误、熔断、恢复和总预算耗尽。
- fallback 不超过配置次数，所有 provider 共享同一 run budget。
- 同一 Replay 输入产生确定性的路由决定和报告证据。

## Issue 31 — Vector Store、Reranker 与检索基准

### 工作内容

- 定义 Embedding Provider、Vector Store 和 Reranker 端口，保持 provider 可替换。
- 实现一个本地持久 Vector Store，索引键绑定 project revision、文件 digest 和 symbol/range。
- 支持增量更新、删除、维度/模型版本隔离和损坏重建。
- 在现有 Exact + BM25 + Graph 基础上接入 vector candidate 与可选 Reranker。
- 扩充检索 fixture，比较 lexical、vector、hybrid、hybrid+r rerank 的 Recall@K、MRR、延迟和成本。

### 验收条件

- 删除或修改源码后不会检索到旧 revision 的 stale chunk。
- Vector provider 不可用时安全降级到 lexical/graph，结果明确标记 degraded，而不是返回空上下文。
- 固定数据集产生可复现对比报告；只有达到预设 Recall/延迟门槛的配置才可成为默认值。

## Issue 32 — Docker 依赖治理与 v0.2 综合验收

### 工作内容

- 将依赖获取拆成受控预热阶段和默认离线执行阶段。
- 使用内容寻址、只读挂载的 Maven 缓存；验证缓存目录、owner、权限和大小上限。
- 支持固定镜像 digest，拒绝未授权 privileged、host network、额外挂载和危险 capability。
- 对超时、磁盘/内存/进程限制、网络拒绝和损坏缓存建立测试矩阵。
- 建立跨 Policy、Capability、MCP 写、模型 fallback、检索与可恢复 Harness 的 v0.2 E2E。
- 更新 threat model、架构、ADR、Quickstart、迁移说明和 v0.2 release checklist。

### 验收条件

- 无网络执行阶段仍能使用预热缓存完成 Banking compile/test。
- 命令构造测试始终包含只读 rootfs、非 root、资源限制、网络策略和显式挂载边界。
- 在可用 Docker daemon 的环境通过真实集成测试；无 daemon 时只允许明确 skip，不能伪报成功。
- 全仓 `clean verify`、静态分析、覆盖率、安全回归和 v0.2 E2E 全部通过。

## 执行顺序

```text
前置：审阅并提交 Issues 21–26
  │
  ├── 27 Policy DSL ── 28 Capability Grant ── 29 MCP 写工具 ──┐
  │                                                            │
  ├── 30 多模型路由与 Fallback ────────────────────────────────┤
  │                                                            ├── 32 Docker / v0.2 E2E
  └── 31 Vector Store 与 Reranker ─────────────────────────────┘
```

实际按安全依赖完成：27–29 先建立策略、授权和 MCP 写闭环；30–31 再加入模型与检索韧性；32 最后完成 Docker 合同和跨模块验收。

## 建议检查点

1. **Checkpoint 0：** Issues 21–26 独立提交，全仓验证结果可追溯。
2. **Checkpoint 1：** Issue 27 默认策略兼容，安全矩阵通过。
3. **Checkpoint 2：** Issues 28–29 完成授权写 MCP 闭环，越权/重放测试通过。
4. **Checkpoint 3：** Issues 30–31 各自产生确定性基准报告，不凭主观样例选默认配置。
5. **Checkpoint 4：** Issue 32 完成有 Docker 与无 Docker 两类验收记录，形成 v0.2 RC 候选提交。

## 明确边界

- 本阶段继续保持 Single Agent，不引入多 Agent 调度。
- MCP 写工具不因“本地客户端”身份自动获得权限。
- Fallback 只提高 provider 可用性，不绕过预算、审批、内容安全或结构化输出要求。
- 不将真实 provider token、capability token 或用户源码写入 fixture、Trace、报告和版本库。
- React Dashboard、OpenTelemetry 和 30+ Eval 数据集留到 v0.3。

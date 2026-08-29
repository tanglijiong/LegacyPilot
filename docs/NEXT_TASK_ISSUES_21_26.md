# 下一长期任务：可恢复的长任务 Harness

**覆盖 Issues：** 21–26

**版本目标：** v0.2 Strong Harness 第一阶段

**状态：** Engineering Complete

**规划日期：** 2026-08-27

**完成日期：** 2026-08-28

## 启动时缺口与后续完成状态

### v0.1 发布动作

本阶段启动时，Issues 01–19 的工程实现与确定性验收已经完成，Issue 20 尚有以下外部发布动作：

1. 使用真实 provider credentials 跑完五任务模型基线，记录模型、Prompt/策略版本、价格表和运行环境；目标至少 4/5。
2. 在全新环境按 Quickstart 完成 30 分钟 smoke task，并修正文档中发现的问题。
3. 在可用 Docker daemon 上运行未执行的 Docker 集成测试。
4. 让远端 GitHub Actions 的 CI、Security 和 Eval smoke 工作流实际通过。
5. 录制不使用 reference overlay 的每日转账限额 GIF/短视频。
6. 审阅上述发布证据后创建并推送 `v0.1.0` tag。

上述事项后来均使用真实凭据、Docker daemon、远端工作流和人工视觉检查完成，并随 `v0.1.0` 发布；证据见 [`RELEASE_CHECKLIST_V0.1.md`](RELEASE_CHECKLIST_V0.1.md)。此列表保留为启动时历史基线，不再表示当前待办。

### 实施前基线

当前已经有文件型 request/checkpoint/approval/report store 和 `agent-resume`，但它只提供基础恢复：

- checkpoint 没有 schema version、迁移策略和兼容性校验；
- 工具调用没有持久化 action journal，进程在“工具成功、checkpoint 未保存”之间退出时可能重复执行；
- 没有 run lease，同一个 run 可能被两个进程同时恢复；
- Trace 使用内存存储，重启后报告无法保留完整事件链；
- Context Builder 只对单次检索做预算装箱，没有跨步骤压缩、摘要版本和任务记忆；
- Policy 仍是按风险枚举硬编码，尚无可配置规则、规则解释和回归矩阵；
- MCP 当前刻意只开放只读与验证工具，受控写工具是 v0.2 候选，不属于 v0.1 欠账。

## 完成结果

| Issue | 已交付能力 | 验收证据 |
| --- | --- | --- |
| 21 | `schemaVersion: 2` 状态 envelope、v0.1 迁移、原子替换、上一版本快照、未知版本拒绝、损坏隔离、状态检查 CLI | 旧格式迁移、截断状态和 100 个确定性中断写入样本通过 |
| 22 | 持久 Action Journal、稳定 action ID、digest 证据链、成功动作跳过、结果不确定时 `NEEDS_REVIEW`、结果限长与脱敏 | 工具调用前后和 checkpoint 保存前故障测试通过，已确认成功动作重复执行数为 0 |
| 23 | owner/epoch/TTL lease、获取/续租/释放/过期接管、fencing、Recovery Coordinator、CLI/REST 恢复入口 | 并发冲突、过期接管、旧 epoch 拒绝以及审批/人工审查状态不越过测试通过 |
| 24 | 按 run 的 append-only JSONL Trace、持久序号分配、文件锁、损坏尾部隔离、集中脱敏 | 100 路并发追加、重启序号连续、敏感信息回归和 MCP 持久序号接入通过 |
| 25 | 五类 Task Memory、TTL/容量/按 run 删除、确定性 Context Compaction、来源和裁剪原因 | 200 步任务始终处于 token budget 内，未完成动作和来源保持可追溯 |
| 26 | 故障注入矩阵、跨审批/重启/压缩的 Banking Replay、恢复运维文档 | 三个强制退出点、8 个 Runtime 实例、4 个补丁各执行一次，最终真实 Maven 测试通过 |

全仓 `clean verify` 已通过：104 个测试，0 failure、0 error、1 个因本机 Docker daemon 不可用而跳过；聚合 JaCoCo 指令覆盖率 89.2%、分支覆盖率 69.6%、行覆盖率 89.0%。Spotless、编译、单元/集成测试、JaCoCo 门禁、SpotBugs 和依赖规则均在同一轮构建中通过。

## 长期任务目标

让一个持续数小时、包含审批与多次工具调用的 Agent Run 在进程退出、重复恢复和上下文增长时仍然安全、可解释、可继续：已确认成功的动作不重复执行，无法确认的动作停在人工审查状态，所有恢复决定都有持久证据。

## Issue 21 — 版本化运行状态与迁移

### 工作内容

- 为 request、checkpoint、approval、report 定义带 `schemaVersion` 的持久化 envelope。
- 加入兼容性校验、从 v0.1 格式迁移和损坏文件隔离。
- 使用原子替换并保留有限数量的上一版本快照。
- 提供状态检查 CLI，输出可恢复、需迁移、已损坏三类结果。

### 验收条件

- v0.1 fixture 可无损迁移并恢复。
- 未知新版本被安全拒绝，不覆盖原数据。
- 截断 JSON、部分写入和迁移中断测试全部通过。

## Issue 22 — Action Journal 与幂等执行

### 工作内容

- 新增持久化 action journal，记录 action ID、digest、输入摘要、状态、尝试次数和结果摘要。
- 状态至少包含 `PREPARED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`NEEDS_REVIEW`。
- 在 ToolExecutor 前后写入 journal；恢复时跳过已成功动作。
- 对 patch 等可验证写入实现效果核对；对无法安全判定的命令禁止自动重放。
- 将 approval digest、plan digest 和 action ID 串成可审计证据链。

### 验收条件

- 在工具执行前、执行后和 checkpoint 保存前注入退出，恢复后不会重复应用补丁。
- 无法证明结果的命令进入 `NEEDS_REVIEW`，不会静默重试。
- journal 不保存明文密钥或无限输出。

## Issue 23 — Run Lease、并发恢复与 Recovery Coordinator

### 工作内容

- 为每个 run 增加带 owner、epoch、过期时间的 lease。
- 实现获取、续租、释放和过期接管，使用可测试 Clock。
- 增加 Recovery Coordinator，扫描非终态 run 并按策略恢复或标记人工处理。
- CLI/REST 返回 lease 冲突、恢复来源和下一安全动作。

### 验收条件

- 两个进程竞争同一 run 时只有一个可以执行。
- owner 异常退出后，过期 lease 可被安全接管。
- 旧 owner 不能凭过期 epoch 写入新 checkpoint。

## Issue 24 — 持久 Trace 与确定性事件序列

### 工作内容

- 用 append-only 文件或 JDBC 实现持久 TraceSink。
- 事件序号由持久 store 原子分配，不再通过读取列表计算。
- 恢复、lease、journal、compaction 和人工接管都写入结构化事件。
- 保留脱敏、大小上限、损坏隔离和 JSON/Markdown 报告能力。

### 验收条件

- 重启前后事件无重复序号、无倒序、无丢失的已确认事件。
- 并发追加测试和敏感信息回归通过。
- 终态报告能引用恢复前后的完整 evidence。

## Issue 25 — Context Compaction 与可删除任务记忆

### 工作内容

- 将历史拆成事实、决策、失败、未完成动作和引用来源五类。
- 在预算阈值触发确定性压缩，保留摘要版本、来源引用和被裁剪原因。
- 增加 task memory store，支持 TTL、按 run/task 删除和最大容量。
- Planner 输入显式区分源码 evidence、历史摘要和未经验证的模型陈述。

### 验收条件

- 200 步合成长任务始终保持在配置 token budget 内。
- 关键约束、未完成动作和引用来源在多次压缩后仍可追溯。
- 删除任务记忆后磁盘和检索层均不可再读取。

## Issue 26 — 长任务故障注入、安全回归与发布证据

### 工作内容

- 建立进程退出、文件截断、重复 resume、lease 过期和工具超时的故障注入矩阵。
- 新增至少一个跨审批、跨重启、跨上下文压缩的 Banking 长任务 Replay。
- 覆盖审批绕过、路径逃逸、命令注入、journal/trace 泄密测试。
- 输出恢复次数、重复动作数、人工审查数、Token、成本和时长报告。
- 更新架构、ADR、Quickstart、迁移与故障排查文档。

### 验收条件

- 至少 100 个确定性故障注入场景通过。
- E2E 在三个以上强制退出点恢复到 `SUCCEEDED`，重复写入数为 0。
- 所有不能证明安全的中间状态都进入人工审查，而不是自动继续。
- 全仓 `clean verify`、静态分析和覆盖率门禁通过。

## 执行顺序

```text
21 版本化状态
 ├── 22 Action Journal ──┐
 └── 23 Run Lease ───────┼── 24 持久 Trace
                         ├── 25 Context Compaction / Memory
                         └── 26 故障注入与 E2E 验收
```

实际按上述依赖顺序完成：21 建立持久化契约，22/23 固化动作与并发恢复语义，24/25 接入持久证据和上下文控制，最后由 26 完成长任务故障验收。

## 明确边界

- 本阶段仍是 Single Agent，不引入多 Agent 调度。
- 不承诺外部命令的理论 exactly-once；无法验证效果时选择 `NEEDS_REVIEW`。
- 不在没有显式授权和审批协议时向 MCP 暴露写工具。
- 不将真实 provider 凭据写入 fixture、Trace、报告或版本库。

# Resilient Harness

Issues 21–26 将基础 `agent-resume` 扩展为可审计、可并发保护的长任务恢复机制。核心原则是：只有能够从持久证据确认效果的动作才自动跳过或重放；结果不确定的写操作进入 `NEEDS_REVIEW`。

## 持久状态布局

默认状态根目录为 `.legacy-pilot/agent`，可通过 `LEGACY_PILOT_AGENT_STATE_ROOT` 修改：

```text
agent/
├── requests/       # 原始 AgentRunRequest
├── checkpoints/    # 最新运行状态
├── actions/        # Action Journal
├── leases/         # owner / epoch / expiresAt
├── traces/         # append-only JSONL Trace
├── memory/         # 带 TTL 的任务记忆
└── reports/        # 终态和暂停态 JSON/Markdown 报告
```

JSON 状态使用 `schemaVersion: 2` envelope。首次读取 v0.1 原始 payload 时会保留 `.v1.bak` 并迁移；未知新版本会被拒绝；损坏内容会保留原文件并复制为 `.corrupt` 供诊断。正常覆盖写保留一个 `.previous` 快照。

## Action Journal

每个工具动作以 step 和 action digest 形成稳定 action ID，并记录：

- `PREPARED`：输入、计划和审批 digest 已绑定；
- `RUNNING`：工具调用已经开始；
- `SUCCEEDED`：已确认成功，恢复时不会重复执行；
- `FAILED`：失败已结构化记录，可按 Runtime budget 决定下一步；
- `NEEDS_REVIEW`：进程退出后无法证明工具效果，禁止静默重放。

Journal 只保存 digest 和经过脱敏、限长的结果摘要，不保存完整敏感输入。

## Lease 与 fencing

每个运行时实例使用唯一 owner 获取 run lease。lease 包含递增 epoch：

1. 活跃 lease 阻止其他 owner 执行同一 run；
2. lease 过期后新 owner 可接管并获得更高 epoch；
3. 旧 owner 无法续租，也无法在下一 checkpoint 前通过 fencing 检查；
4. 正常退出把 lease 写成过期状态，保留 epoch 历史。

文件实现同时使用 JVM 内锁和操作系统文件锁，覆盖同进程多实例与多进程竞争。

## Trace 与恢复决策

Trace 使用按 run 分隔的 append-only JSONL 文件。事件序号在文件锁内分配，重启后继续递增；损坏的最后一行被隔离为 `.corrupt-tail`，之前确认的事件仍可读取。所有属性继续经过集中脱敏。

Recovery Coordinator 对状态进行分类：

| 状态 | 自动行为 |
| --- | --- |
| Terminal | 返回 `TERMINAL`，不再次执行 |
| `WAITING_FOR_APPROVAL` | 返回 `AWAITING_APPROVAL` |
| `NEEDS_REVIEW` | 返回 `NEEDS_REVIEW` |
| 其他非终态 | 获取 lease 后调用持久 request 恢复 |
| 恢复异常 | 返回不含内部敏感信息的 `FAILED` |

## Context Compaction 与任务记忆

工具结果按事实、决策、失败、未完成动作和源码证据分类，带来源、verified 标记、创建时间和 TTL。每一步调用 Planner 前执行确定性压缩：未完成动作和决策优先，过长内容降级为带来源的摘要，超出预算的条目记录淘汰原因。

任务记忆有容量上限，可按 task/run 删除；删除同时清理当前文件、上一版本、迁移备份和损坏副本。

## 运维命令

```bash
# 检查 request/checkpoint 是否缺失、旧版、当前、未知或损坏
java -jar apps/cli/target/legacy-pilot-cli-0.3.0-SNAPSHOT.jar \
  agent-state-check RUN_ID

# 扫描非终态运行；审批态和人工审查态不会自动越过
java -jar apps/cli/target/legacy-pilot-cli-0.3.0-SNAPSHOT.jar \
  agent-recover
```

服务端也提供 `POST /api/v1/agent-runs/recovery`，返回每个 run 的恢复决策、当前状态和下一步说明。

## 故障验证

确定性测试覆盖 100 个中断写入样本、100 路并发 Trace、lease 过期接管、旧 epoch fencing、敏感结果脱敏和 200 步上下文压缩。Banking 每日限额 E2E 在三个位置注入退出：action 成功后、action 标记 RUNNING 后、checkpoint 保存前；随后跨 8 个 Runtime 实例恢复，最终通过真实 Maven 测试且四个补丁各执行一次。

## 安全边界

- 外部命令不承诺理论上的 exactly-once；不能确认时进入人工审查。
- `agent-recover` 不会越过审批或 `NEEDS_REVIEW`。
- MCP 仍只开放只读和验证工具，未借恢复能力隐式开放写权限。
- 状态目录必须被视为本地敏感运行数据，不进入 Git。

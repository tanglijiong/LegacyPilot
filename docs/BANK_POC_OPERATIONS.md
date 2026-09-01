# 银行内网 PoC 运维、备份、升级与回滚

## 1. 目录和所有权

建议使用独立 `legacypilot` 系统账号，`umask 0077`：

| 路径 | 内容 | 备份 |
| --- | --- | --- |
| `/opt/legacypilot` | 签名 JAR、版本和校验和 | 每版本保留 |
| `/etc/legacypilot` | 非秘密配置和 Policy | 加密备份 |
| `/var/lib/legacypilot/work` | repository cache 和 worktree | 通常不备份，可从固定 revision 重建 |
| `/var/lib/legacypilot/state` | checkpoint、journal、lease、trace、report | 必须备份 |
| `/var/lib/legacypilot/m2` | 审批后的离线 Maven 缓存 | 可重建，建议保留快照 |
| `/run/legacypilot/model` | 临时 Unix socket 和 service manifest | 不备份 |

秘密只通过机构 secret manager、systemd credentials 或权限受控的环境文件提供。

## 2. 启停顺序

启动：PostgreSQL → rootless Docker → vLLM/DeepSeek → LegacyPilot Server → 反向代理。停止顺序相反；停止前不再接收新任务，等待运行进入稳定 checkpoint，超时任务执行受控取消。

健康检查至少验证：数据库迁移完成、模型 socket manifest 匹配、Docker 可用、磁盘余量、只读/无网 sandbox smoke、OIDC JWT 和审计落盘。

## 3. 备份和恢复

一致性备份步骤：

1. 禁止启动新运行并记录维护窗口。
2. 等待运行完成、暂停或进入 `NEEDS_REVIEW`。
3. 对 PostgreSQL 执行机构标准的加密逻辑/物理备份。
4. 对 state 和配置卷创建同一时间点快照。
5. 记录应用版本、数据库 schema、Policy、模型、镜像和 SHA-256。
6. 将备份写入不可变存储并执行恢复校验。

恢复时先恢复 PostgreSQL，再恢复匹配的 state 卷和应用版本；运行 `agent-state-check`，随后执行 recovery scan。不得自动重放 `NEEDS_REVIEW` 动作。

建议 PoC 指标：RPO 24 小时、RTO 4 小时；银行可以根据重要度收紧。

## 4. 升级和回滚

升级前必须：完整备份、生成 SBOM/校验和、运行固定 20 题 ceiling、5 题真实模型 smoke、数据库迁移预演和安全扫描。

升级步骤：停止接单 → 备份 → 校验候选 artifact → 启动新版本 → Flyway migrate → 运行只读检查与 smoke → 开放用户。

若迁移仅向前兼容，可切回旧 JAR；存在不兼容数据库迁移时，必须恢复升级前数据库和 state 快照，不能只替换 JAR。模型、Prompt、Policy 或镜像变更也视为一次独立升级，不能与应用大版本混在同一未隔离变更中。

## 5. 监控和告警

至少监控：任务状态、运行队列、等待审批时间、`NEEDS_REVIEW`、失败分类、模型错误率、sandbox 失败率、Token/时长预算、数据库连接、磁盘、Maven 缓存、容器 CPU/内存/PID、审计写入失败和 egress 拒绝事件。

必须告警：审计无法落盘、Policy 加载失败、镜像/权重摘要不匹配、数据库迁移失败、模型 socket 被替换、同一 action 重复、不确定写操作、磁盘接近满、容器出现网络连接。

## 6. 故障处理

- 模型不可用：停止启动新任务；已落盘 checkpoint 保留；恢复模型后显式 resume。
- Docker 不可用：验证失败，不得把任务标为成功。
- 数据库不可用：API 返回失败并停止状态变更；不得以本地文件替代业务生命周期数据。
- state 文件损坏：隔离损坏文件，保留备份和哈希，进入人工恢复。
- 不确定写结果：进入 `NEEDS_REVIEW`，审计员确认 worktree 和 Journal 后决定恢复或丢弃。
- 凭据疑似泄漏：停止服务、轮换、扫描 Git/Trace/备份、保留事件证据。

## 7. 容量基线

PoC 起点建议：Server 4 vCPU/8 GiB、PostgreSQL 2 vCPU/4 GiB、每个 Maven sandbox 2 vCPU/4 GiB、独立加密数据卷 100 GiB。GPU 和模型内存以批准权重实际测量为准；DeepSeek Coder V2 Lite 示例不能代替机构容量测试。默认并发保持 1–2，只有 p95、内存和 provider 错误率证据允许时才提高。

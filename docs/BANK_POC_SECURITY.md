# 银行内网 PoC 安全架构、数据流与威胁模型

## 1. 适用范围

本文用于金融机构对 LegacyPilot 单节点内网 PoC 开展架构和安全评审。它不替代机构自身的等级保护、数据分类分级、供应链、模型治理和变更管理流程。

LegacyPilot 只处理研发代码副本、任务描述、模型上下文、补丁、构建输出和审计证据。它不需要生产数据库凭据，不访问生产业务流量，也不自动推送、合并或部署代码。

## 2. 信任边界与数据流

```text
[用户浏览器/CLI]
    | TLS + OIDC JWT
    v
[反向代理] -------------------- [银行 IdP/JWK]
    |
    v
[LegacyPilot Server / 控制面]
    |-- JDBC/TLS -----------> [PostgreSQL]
    |-- 本地加密卷 ---------> [worktree/checkpoint/trace/report]
    |-- 固定工具请求 --------> [rootless Docker Maven sandbox]
    |                             | network none
    |                             v
    |                         [任务代码副本]
    |
    +-- Unix socket --------> [vLLM/DeepSeek model service]
                                  | network none
                                  +-- 只读模型权重
```

独立控制点：

- 网络层由宿主防火墙拒绝 Server、sandbox 和模型服务的非批准 egress。
- 应用层仅允许 OIDC 认证、角色授权、Policy 和 Capability 许可的行为。
- 执行层使用独立 worktree、只读根文件系统、非 root、能力移除和资源限制。
- 证据层记录 actor、关联 ID、计划、工具、审批、模型与验证结果，并集中脱敏。

## 3. 数据分类与落点

| 数据 | 默认敏感度 | 存储位置 | 外发 |
| --- | --- | --- | --- |
| 源代码副本 | 机构机密 | 任务 worktree 加密卷 | 禁止 |
| 需求和验收条件 | 内部/机密 | PostgreSQL、checkpoint | 禁止 |
| Prompt 与选中上下文 | 机密 | 运行状态和模型内存 | 仅 Unix socket 到本地模型 |
| 补丁和测试输出 | 机密 | worktree、报告卷 | 仅经授权下载 |
| Token/成本/耗时 | 内部 | Trace/Eval artifact | 可脱敏汇总 |
| 身份与审批记录 | 审计数据 | PostgreSQL、Trace | 可输出 SIEM |
| 凭据和私钥 | 最高敏感 | 银行 secret manager | 不进入 LegacyPilot artifact |

运行目录、原始 Prompt、模型输出、数据库备份和日志均不得直接提交 Git。发布基线必须复制到独立目录后人工脱敏审阅。

## 4. 身份、权限与职责分离

JWT 使用 `roles` claim，映射为以下角色：

| 角色 | 权限 |
| --- | --- |
| `DEVELOPER` | 注册项目、创建任务、启动/恢复/取消运行 |
| `APPROVER` | 查看计划和证据、批准或拒绝受限动作 |
| `AUDITOR` | 只读查看项目、任务、状态、报告和审计证据 |
| `ADMIN` | Capability 管理、恢复扫描、受控兼容接口和 OpenAPI |

审批 actor 和 Capability subject 来自经过验证的 JWT subject，忽略客户端自报身份。数据库记录执行发起人；同一身份不能审批自己发起的运行。银行 IdP 应禁止共享账号，并对管理员和审批人启用强认证。

## 5. 主要威胁与控制

| 威胁 | 主要控制 | 残余风险/机构动作 |
| --- | --- | --- |
| 未认证调用管理 API | 默认 deny-all；OIDC/JWT；RBAC | IdP、反向代理和证书由机构配置 |
| 发起人自批或伪造 actor | actor 取自认证上下文；数据库职责分离 | 机构需治理共享账号和角色审批 |
| 路径穿越/符号链接逃逸 | real path、marker、root 边界、symlink 拒绝 | 不导入不可信宿主挂载 |
| 任意命令执行 | 只注册参数化 Maven 工具；Policy；审批 | Maven 插件本身仍是不可信代码 |
| 恶意 Maven 构建 | rootless Docker、无网、只读 rootfs、资源限制 | 执行宿主必须专用；Docker 权限等价高权限 |
| Prompt injection | 最小上下文、固定工具、Policy、验证器终态 | 不允许模型绕过审批；人工复核高风险补丁 |
| 代码/Prompt 外泄 | 模型 Unix socket；容器无网；宿主防火墙 | 必须独立验证 egress 和代理环境为空 |
| 模型或镜像被替换 | image digest、权重 SHA-256、只读挂载、manifest | 机构负责签名验证和内部仓库准入 |
| 供应链依赖污染 | 离线 Maven 缓存、SBOM、Gitleaks、CodeQL | 机构执行 SCA、恶意软件扫描和风险接受 |
| 重放或重复写入 | stable action ID、Journal、Lease、一次性 Capability | 不确定结果进入 `NEEDS_REVIEW` |
| 日志泄露 | 集中 Redactor、输出上限、原始 run 目录忽略 | SIEM 侧再次执行访问控制和保留策略 |
| 拒绝服务/费用失控 | 步骤、Token、成本、时长、PID/CPU/内存预算 | 机构设置主机配额和并发门槛 |
| 数据库或磁盘损坏 | Flyway、原子状态文件、损坏隔离 | 机构执行加密备份和恢复演练 |

## 6. 端口和网络矩阵

| 源 | 目标 | 端口/协议 | 默认策略 |
| --- | --- | --- | --- |
| 用户区 | 反向代理 | 443/TCP | 允许已认证用户网段 |
| 反向代理 | Server | 8080/TCP loopback/管理网 | 仅代理允许 |
| Server | IdP/JWK | 443/TCP | 仅 OIDC 端点；可用离线 JWK 镜像替代 |
| Server | PostgreSQL | 5432/TCP | 仅数据库主机，建议 TLS |
| Server | vLLM | Unix socket | 仅本机文件权限允许 |
| Server | Docker daemon | rootless Unix socket | 仅专用服务账号 |
| Sandbox | 任意网络 | 无 | `network none` + 宿主防火墙拒绝 |
| Model service | 任意网络 | 无 | `network none` + 宿主防火墙拒绝 |

除了 OIDC/JWK 和数据库，没有应用必需的 IP egress。完全隔离区应在区内镜像 JWK 或采用银行批准的离线 JWT 验证配置。

## 7. 审计事件和保留

最低审计集合：登录主体、关联 ID、项目 revision、任务和 run ID、计划摘要、Action digest、Policy revision、审批决策、Capability 发放/使用/吊销、工具状态、模型/Prompt/镜像/权重摘要、验证结果、恢复和取消事件。

审计导出前继续脱敏，禁止记录 JWT、Authorization、数据库口令、代理凭据或完整私钥。建议 PoC 在线保留 90 天、归档保留 1 年；最终期限由机构制度决定。删除任务时应同时处理 worktree、checkpoint、memory、trace、report、数据库记录和备份生命周期。

## 8. 明确残余风险

- 当前为单节点部署，不提供分布式一致性或自动故障切换。
- Docker 执行宿主必须被视为高信任专用基础设施。
- 模型生成结果可能错误；只有确定性验证通过并经人工审查后才能进入机构代码流程。
- 本项目不声明满足某一特定国家、地区或机构的全部监管认证。

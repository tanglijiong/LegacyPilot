# LegacyPilot 银行内网 PoC 交付计划与状态台账

**目标版本：** `v0.3.0-bank-poc`
**状态：** Scope frozen — target-environment validation pending
**最后更新：** 2026-09-01
**冻结分支：** `codex/v0.3-bank-poc-release`
**工程实现基线：** `26465f85a5f80a79f0f21ab145678ddbbafd85ed`
**部署与验收基线：** `cc96b1e`（包含 `04f774d9f4db0f1f3658502cc4677cc85c3a2506` 的部署包，并加固三类镜像、模型权重和证据范围门禁）

在阶段 4 和阶段 7 的技术门禁通过前，不创建正式版本标签、不发布候选物，也不把 reference ceiling 记作真实模型成绩。该参考方案不要求任何银行人员或外部机构签署；后续代码变更必须重新执行完整证据构建并更新上述基线，最终完整源码身份由签名发布标签确定。

## 1. 完成定义

本计划不以在某家银行的真实环境上线为完成条件。完成指 LegacyPilot 已形成一套可由金融机构在其内网自行安装、配置、验证和审计的参考解决方案，并满足以下条件：

1. 用户可通过统一 API 或 CLI 完成“注册项目 → 创建任务 → 建立索引 → 生成计划 → 审批 → 受控执行 → 验证 → 获取补丁与报告”。
2. REST 管理面默认启用认证，具备管理员、开发者、审批人和审计员角色；审批者身份来自认证上下文，并执行职责分离。
3. 本地模型和任务 Agent 在零外网容器中运行；镜像、模型权重、Prompt、Policy 和实验数据均有不可变摘要。
4. 固定 20 题数据集具备真实内网模型 pass@1、一次公开反馈后的最终成绩、失败分类、成本、Token 和耗时证据。
5. 能生成基线/候选 A/B 比较、回归门禁、Markdown/JSON 报告和离线静态 Dashboard。
6. 提供单节点 PostgreSQL、LegacyPilot Server、执行沙箱及 vLLM/DeepSeek 的参考拓扑和离线部署材料。
7. 提供威胁模型、数据流、端口矩阵、SBOM、第三方许可证、备份恢复、升级回滚、监控审计和验收手册。
8. 全量构建、安全扫描、真实 Docker 集成、干净环境安装和端到端验收通过，形成可校验的候选发布物。

## 2. 明确边界

- 不要求连接公网模型或部署到公有云。
- 不要求自动推送、合并或发布银行业务代码。
- 不访问生产数据库，不直接变更生产环境。
- 不建设多租户 SaaS、计费系统、多 Agent 集群或复杂前端。
- Kubernetes、Jira、Jenkins、IDE 和特定厂商 SSO 只保留标准接口或示例，不作为本版本门槛。

## 3. 阶段状态

| 阶段 | 状态 | 退出条件 |
| --- | --- | --- |
| 0. 完成定义与台账 | DONE | 本文档进入仓库，范围、交付物和验收口径固定 |
| 1. 统一执行闭环 | DONE | 项目 task/run 可自动进入索引、Agent、审批、恢复、验证和报告，不再要求客户端构造内部 `ProjectIndex` |
| 2. 企业身份与职责分离 | DONE | 默认认证、RBAC、可信 actor、申请/审批分离和安全 API 测试通过 |
| 3. Eval 证据产品化 | DONE | 失败分类、A/B 比较、门禁和单文件离线 Dashboard 完成 |
| 4. DeepSeek 真实基线 | BLOCKED-ENV | 需要经批准的固定镜像与本地模型权重；先完成 5 题 smoke，再运行 20 题全量 |
| 5. 参考部署包 | DONE | PostgreSQL 单节点参考拓扑、离线物料、配置与验收脚本完整 |
| 6. 银行安全与运维材料 | DONE | 威胁模型、数据流、端口、SBOM、审计、备份和升级材料完整 |
| 7. 候选版本验收 | BLOCKED-ENV | 确定性门禁与本机真实 Docker 已通过；DeepSeek、OIDC 和目标主机恢复演练等待批准环境 |

`BLOCKED-ENV` 只表示该阶段依赖仓库外的模型权重和 GPU/容器环境；它不会阻止其他阶段继续实施。没有真实运行证据时，不得把 reference ceiling 宣称为模型成绩。

当前环境检查：Docker Desktop 28.4.0 已可用，两项真实 Docker 沙箱测试和一项 PostgreSQL 16 应用集成测试均已执行并通过；仍未发现批准的内网 Agent 镜像、`MODEL_ARTIFACT_SHA256` 或本地 DeepSeek 权重，因此不得启动或伪造阶段 4 的实验。主 Agent Runtime 已支持与 Eval 相同的 OpenAI-compatible Unix socket 模型边界，待批准环境就绪后可直接验收。

本机确定性证据（2026-09-01）：完整 Maven reactor 24/24 成功，独立空源码目录复验同样通过；Reactor 内 Java 测试 170 项全部执行，失败 0、错误 0、跳过 0，其中两项 Maven sandbox 和一项 PostgreSQL 16 真实 Docker 集成通过。真实库门禁发现并修复了 PostgreSQL 不支持 `CLOB` 以及缺失 Flyway PostgreSQL 模块的问题，并验证 V1/V2 迁移、项目/任务/run API 与执行发起人记录；同时保留已发布 H2 V1 的原始校验和，并通过从 v0.2 数据库升级到 V2 的回归测试。PostgreSQL 16 参考 Compose 另已以完整镜像 digest 完成健康检查、写入后重启持久化和 `pg_dump` 逻辑恢复校验，烟测资源已清理；DeepSeek 离线契约 4/4；`v0.3-core.1` reference ceiling 20/20；CycloneDX 1.6 SBOM 含 123 个组件。`build-evidence.sh` 缺少 digest 时退出 2，使用本机完整 Maven/PostgreSQL digest 时可从干净构建生成非空 SBOM、第三方许可、依赖树和 SHA-256 清单。本机输出用于验证参考方案的可复现性。真实 Bearer JWT 测试已验证 OIDC `roles` claim 和 subject 信任边界；统一执行路径已进入受管理员保护的 OpenAPI 契约，应用与安全错误具有稳定错误码和 correlation ID；示例见 [银行内网 PoC API 契约与操作示例](BANK_POC_API.md)。候选发布状态详见 [银行 PoC 发布检查表](BANK_POC_RELEASE_CHECKLIST.md)。

## 4. 交付物清单

### 产品与接口

- 统一任务执行 API/CLI
- 审批、恢复、取消、状态和报告接口
- OpenAPI 契约和示例请求
- 机器可读错误码与关联 ID

### 安全与治理

- OIDC/JWT 生产配置和仅用于本地演示的受控认证配置
- RBAC 与职责分离矩阵
- Policy 与 Capability 配置样例
- 审计导出、脱敏、保留和删除策略
- 威胁模型、数据流和残余风险

### 模型与评测

- 离线 DeepSeek/vLLM 镜像构建与启动契约
- 5 题 smoke 和 20 题完整实验
- A/B comparison JSON、Markdown 和 HTML Dashboard
- Prompt、Policy、镜像、权重、数据集和价格/资源快照

### 部署与运维

- PostgreSQL + Server + Docker Sandbox + Model Service 参考拓扑
- 离线镜像、Maven 缓存和依赖清单
- TLS、证书、端口、卷和资源配置
- 备份、恢复、升级、回滚、监控和故障排查手册
- SBOM、SHA-256 和第三方许可证清单

## 5. 验收原则

- 默认拒绝，不能通过省略配置退化为无认证或开放网络。
- 验收以确定性编译、测试、断言和策略检查为准，不由 LLM 自行判定成功。
- 每项状态变更必须同时更新本文档、对应测试和发布清单。
- 外部依赖缺失时保留可复现命令、输入摘要和明确阻塞条件，不伪造结果。
- 候选版本不得包含凭据、真实银行代码、未脱敏 Prompt、原始模型输出或本机绝对路径。

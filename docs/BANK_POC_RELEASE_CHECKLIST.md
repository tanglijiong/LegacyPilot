# LegacyPilot 银行内网 PoC 发布检查表

**候选版本：** `v0.3.0-bank-poc`
**检查日期：** 2026-09-01
**发布决定：** 暂不签署候选版本；等待银行批准的离线模型与容器主机完成环境验收。

本检查表区分“解决方案已实现”和“目标环境已验证”。没有真实环境证据的条目保持阻塞，不以模拟结果替代。

## 候选追溯基线

- 冻结分支：`codex/v0.3-bank-poc-release`
- 工程实现提交：`26465f85a5f80a79f0f21ab145678ddbbafd85ed`
- 部署与验收提交：`cc96b1e`（基于 `04f774d9f4db0f1f3658502cc4677cc85c3a2506`）
- 本机复验镜像：Maven `sha256:8f6ac126f7810bb5549c4cd122d2bf0e9cda5bdeb0838aa928f09e779fd8bef8`；PostgreSQL `sha256:20edbde7749f822887a1a022ad526fde0a47d6b2be9a8364433605cf65099416`。二者仅用于本机复验，尚未取得银行批准。
- 证据摘要由构建生成在 `target/bank-poc-evidence/`，最终值写入发布包外层的签名清单，不回写到被摘要覆盖的仓库文件，避免循环摘要和过期值。
- 发布纪律：阶段 4、阶段 7 和四方签署完成前，不创建正式标签或对外发布。

## 已通过

- [x] 统一 run 执行闭环由服务端完成索引、规划、受控执行、审批恢复、验证和报告。
- [x] 统一执行路径已进入管理员保护的 OpenAPI；提供完整调用示例、稳定错误码和响应体/响应头 correlation ID。
- [x] REST 默认拒绝访问，OIDC/JWT、`ADMIN`、`DEVELOPER`、`APPROVER`、`AUDITOR` RBAC 已实现；真实 Bearer JWT 测试覆盖 `roles` claim 与可信 subject。
- [x] 执行发起人和审批人职责分离；actor/subject 取自认证上下文。
- [x] 主 Agent Runtime 与 Eval 均可仅通过 OpenAI-compatible Unix socket 使用本地模型。
- [x] Maven sandbox 镜像在银行配置中强制使用 SHA-256 固定摘要。
- [x] 两项 Maven sandbox 真实 Docker 集成测试在 Docker Desktop 28.4.0 中执行通过；无跳过。修复了 Maven/Jansi `noexec` 兼容性，并验证联网最小预热、重试、只读缓存、完整镜像 digest 和无网离线执行。
- [x] 一项 PostgreSQL 16 真实 Docker 应用集成测试通过；修复 `CLOB` 方言和 Flyway PostgreSQL 模块缺口，验证 V1/V2 迁移、项目/任务/run API 与职责分离持久化。
- [x] 失败分类、A/B 比较、回归门禁、JSON/Markdown/单文件离线 Dashboard 已完成。
- [x] PostgreSQL 单节点、systemd、策略、环境样例、预检和证据生成脚本已提供。
- [x] PostgreSQL 参考 Compose 已在本机按完整 digest 通过健康检查、写入后重启持久化和 `pg_dump` 恢复校验；专用容器与卷已清理。该结果验证部署材料，不等同于目标主机或银行镜像审批。
- [x] 威胁模型、数据流、端口矩阵、运维、验收、备份恢复和升级回滚说明已提供。
- [x] Maven reactor 24/24 成功；Reactor 内 Java 测试 170 项全部执行，失败 0、错误 0、跳过 0。
- [x] 排除 `.git`、构建目录和本地运行状态后，在独立空源码目录完整复验通过。
- [x] DeepSeek 离线 Agent 契约测试 4/4 通过。
- [x] `v0.3-core.1` reference ceiling 20/20 通过；该结果仅证明数据集可解。
- [x] CycloneDX 1.6 SBOM（123 个组件）、Maven 依赖树、SHA-256 清单和第三方许可清单可离线生成；银行证据脚本要求 Maven/PostgreSQL 测试镜像均使用已批准完整 digest 且已存在于离线镜像库。
- [x] `build-evidence.sh` 已验证失败关闭和本机完整 digest 正向路径，可从干净构建生成四类非空证据文件；本机镜像未标记为银行批准。
- [x] 主机预检对 Maven、PostgreSQL、DeepSeek Agent 三类离线镜像执行完整 digest 与本地存在性检查，并校验只读模型权重、批准摘要标记、数据库密码文件和模型 Unix socket；非法摘要或缺失镜像均失败关闭。

## 环境阻塞项

- [ ] 提供批准且按 digest 固定的 Maven sandbox、vLLM/DeepSeek Agent 和 PostgreSQL 镜像。
- [ ] 提供只读 DeepSeek 权重目录及经审批的 `MODEL_ARTIFACT_SHA256`。
- [ ] 运行 task-001–005 DeepSeek smoke，达到至少 4/5 且基础设施错误为 0。
- [ ] 使用新的不可变 run ID 运行 20 题真实 DeepSeek 基线，记录 pass@1、反馈后成绩、失败、Token、成本和 p50/p95。
- [ ] 对基线与候选策略运行 `eval-compare` 并通过发布门禁。
- [ ] 在空目录/目标主机执行 PostgreSQL + state 备份恢复、JAR/Policy 升级回滚和完整统一 API E2E。
- [ ] 由银行安全、模型、运维和业务验收责任人签署证据包与残余风险。

## 解阻输入

执行剩余验收需要银行或测试环境提供：可用 rootless Docker/GPU 主机、三个批准镜像的完整 digest、DeepSeek 权重路径与 SHA-256、OIDC 测试租户、PostgreSQL 密钥文件、内部 CA/TLS 配置及四个验收身份。所有输入应通过机构批准的安全渠道提供，不写入仓库。

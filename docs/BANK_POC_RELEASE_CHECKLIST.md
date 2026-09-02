# LegacyPilot 银行内网 PoC 发布检查表

**候选版本：** `v0.3.0-bank-poc`
**检查日期：** 2026-09-02
**发布决定：** 工程与本地部署已完成，但暂不发布正式版本；真实 DeepSeek 全量成绩为 9/20（45%），未达到既定 70% 模型质量门槛。

本检查表区分“解决方案已实现”和“目标环境已验证”。没有真实环境证据的条目保持阻塞，不以模拟结果替代。

## 候选追溯基线

- 冻结分支：`codex/v0.3-bank-poc-release`
- 工程实现提交：`26465f85a5f80a79f0f21ab145678ddbbafd85ed`
- 部署与验收提交：`cc96b1e`（基于 `04f774d9f4db0f1f3658502cc4677cc85c3a2506`）
- 本机复验镜像：Maven `sha256:8f6ac126f7810bb5549c4cd122d2bf0e9cda5bdeb0838aa928f09e779fd8bef8`；PostgreSQL `sha256:20edbde7749f822887a1a022ad526fde0a47d6b2be9a8364433605cf65099416`。二者已固定摘要并用于本机复验，不代表真实DeepSeek模型成绩。
- 证据摘要由构建生成在 `target/bank-poc-evidence/`，最终值写入发布包外层的签名清单，不回写到被摘要覆盖的仓库文件，避免循环摘要和过期值。
- 发布纪律：阶段 4和阶段7的技术门禁完成前，不创建正式标签或对外发布；不要求银行人员或外部机构签署。
- 本地模型：`hf.co/bartowski/deepseek-coder-v2-lite-instruct-gguf:q4_k_m`，15.7B、10.4 GB；Docker Model Runner/llama.cpp 在 Apple M2 Max 上运行，不依赖 CUDA。
- 真实评测提交基线：`0d69f91`；完整候选 run：`deepseek-local-full-20260902-v8`；对比目录：`evals/runs/deepseek-local-comparison-v6-v8-corrected/`。

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
- [x] Maven reactor 24/24 成功；Reactor 内 Java 测试 177 项全部执行，失败 0、错误 0、跳过 0；Spotless、Checkstyle、SpotBugs、JaCoCo 全部通过。
- [x] 排除 `.git`、构建目录和本地运行状态后，在独立空源码目录完整复验通过。
- [x] DeepSeek 离线 Agent 契约测试 6/6 通过。
- [x] `v0.3-core.1` reference ceiling 20/20 通过；该结果仅证明数据集可解。
- [x] CycloneDX 1.6 SBOM（123 个组件）、Maven 依赖树、SHA-256 清单和第三方许可清单可离线生成；银行证据脚本要求 Maven/PostgreSQL 测试镜像均使用已批准完整 digest 且已存在于离线镜像库。
- [x] `build-evidence.sh` 已验证失败关闭和本机完整 digest 正向路径，可从干净构建生成四类非空证据文件；本机镜像仅作为可复验的开发证据。
- [x] 主机预检对 Maven、PostgreSQL、DeepSeek Agent 三类离线镜像执行完整 digest 与本地存在性检查，并校验只读模型权重、批准摘要标记、数据库密码文件和模型 Unix socket；非法摘要或缺失镜像均失败关闭。

## 本轮已完成的 DeepSeek 实测

- [x] 本机 `Q4_K_M` 模型下载完成；loopback OpenAI-compatible `/models` 与 `/chat/completions` API 验证通过。
- [x] smoke run `deepseek-local-smoke-20260902-a9`：4/5，达到 smoke 门槛。
- [x] 两个不可变 20 题 run 已完成：v6 与 v8 均为 9/20；候选记录 93,382 Token、本地推理成本 0 美元、p50 10.883 秒、p95 31.941 秒。
- [x] `eval-compare` 已生成 JSON、Markdown 和离线 Dashboard；没有任务状态回归。11 个失败项被正确归类为断言 2、测试 3、编译 6；门禁失败原因仅为候选成功率 45% 低于 70%。
- [x] 格式修正后完整 `mvn verify` 再次通过，包含安全静态检查、Docker sandbox 与 PostgreSQL 集成回归。

## 唯一剩余发布门槛

- [ ] 将真实本地 DeepSeek 的 20 题成功率从 45% 提升到至少 70%，并用新的不可变 run 与 comparison 证明。当前不是 OIDC、证书、身份、审批、CUDA 或银行环境造成的阻塞，而是本地 `DeepSeek-Coder-V2-Lite-Instruct Q4_K_M` 的实际代码任务质量未达标。现有完整失败证据保留，不以 reference ceiling 替代。

## 不属于技术发布门槛

银行 OIDC、内部 CA/TLS 证书、数据库正式密钥、四类测试身份、人员验收签字、模型授权/审批摘要、固定 digest 的机构镜像、CUDA GPU 服务器和目标主机恢复演练，仅在未来真实部署到具体机构时按需配置，不影响本地技术版本完成与发布。

# LegacyPilot 架构决策记录

此文件记录当前规划阶段已经确定的决策。实现阶段如需改变决策，应在 `docs/adr/` 新建完整 ADR，并在此更新状态。

## ADR-001：使用 Single Agent + Strong Harness

- **状态：** Accepted
- **决策：** v0.1-v0.3 只实现一个 Agent Runtime，不拆 Planner/Developer/Tester 等多 Agent。
- **原因：** 项目价值在状态管理、上下文、工具权限、验证、恢复和评测；多 Agent 会放大成本与调试复杂度，且不能自动提升可靠性。
- **后果：** 不同职责通过 Runtime 组件和状态阶段表达，而不是多个自治 Agent。

## ADR-002：Java 21 + Spring Boot + Spring AI

- **状态：** Accepted
- **决策：** 核心工程使用 Java 21 和 Spring Boot；模型适配优先使用 Spring AI，但核心领域与 Runtime 不依赖其具体类型。
- **原因：** 项目需要突出 Java AI Engineering，同时保留模型厂商可替换性。
- **后果：** 引入 `model-spi` 和 Adapter；禁止在 domain 中出现模型厂商 SDK 类型。

## ADR-003：采用 Maven 多模块 Monorepo 与模块化单体

- **状态：** Accepted
- **决策：** 代码放在单仓库，使用 Maven 多模块管理，v0.1 作为单部署单元运行。
- **原因：** 便于本地开发、架构展示、端到端测试和开源贡献；暂时没有独立扩缩容需求。
- **后果：** 通过模块依赖和架构测试维持边界，而不是网络 API。

## ADR-004：AST + BM25 为必选基线，Vector 为可插拔增强

- **状态：** Accepted
- **决策：** 核心检索必须在无向量数据库时运行；精确符号、Java AST、BM25 和依赖图构成基线。
- **原因：** 代码关系具有结构性；只靠 Embedding 不可靠，也会提高运行门槛。
- **后果：** v0.1 可以本地零基础设施启动；向量检索必须通过 Eval 证明增益。

## ADR-005：任务只修改独立 Git worktree

- **状态：** Accepted
- **决策：** Agent 不直接修改用户当前工作区，每个 Run 使用固定 revision 的独立 worktree。
- **原因：** 便于隔离、审计、丢弃和生成 diff，也降低破坏用户工作的风险。
- **后果：** 需要 Workspace 生命周期管理；接受补丁由用户显式完成。

## ADR-006：命令通过类型化工具执行，不开放任意 Shell

- **状态：** Accepted
- **决策：** Maven、Git 等命令使用参数化工具与白名单，不提供通用 `run_shell(command)` 给模型。
- **原因：** 任意 shell 难以安全审计，容易产生注入和环境破坏。
- **后果：** 新命令能力需实现或配置明确工具；灵活性换取可控性。

## ADR-007：Docker 为命令执行边界，Git worktree 为变更边界

- **状态：** Accepted
- **决策：** v0.1 使用双层隔离：worktree 管理变更，Docker 限制构建进程。
- **原因：** 单用 worktree 无法限制恶意/错误构建脚本，单用容器不利于 diff 和用户审查。
- **后果：** 本地开发需要 Docker；后续可增加受约束的非 Docker 开发模式，但不能作为默认安全模式。

## ADR-008：验证器拥有成功终态决定权

- **状态：** Accepted
- **决策：** LLM 不可直接把任务标记成功；只有 Verification Engine 的 required checks 全部通过才能成功。
- **原因：** 模型输出不是可部署证据。
- **后果：** 验证项必须结构化、可配置且有证据；LLM Review 只能是补充项。

## ADR-009：v0.1 先交付 CLI/REST，Dashboard 延至 v0.3

- **状态：** Accepted
- **决策：** MVP 用 CLI、REST 和 Markdown/JSON 报告完成用户流程。
- **原因：** 首要风险是 Agent 能否可靠完成任务，而不是 UI。
- **后果：** README 演示可先使用终端录屏和报告；观测 Dashboard 在 AgentOps 阶段建设。

## ADR-010：MCP 复用 Tool Runtime 与 Policy

- **状态：** Accepted
- **决策：** MCP Server 只做协议适配，不能绕过 Tool Registry、Workspace 和 Execution Policy。
- **原因：** 两套工具实现会产生权限差异和审计漏洞。
- **后果：** MCP 暴露范围是 Tool 能力的受限子集；写操作 v0.1 默认不开放。

## ADR-011：Eval 以确定性结果为主

- **状态：** Accepted
- **决策：** 编译、测试、AST、文件内容和行为断言优先；LLM-as-a-judge 只用于难以确定性判断的辅助维度。
- **原因：** 作品项目需要可重复、可解释的质量证据。
- **后果：** Eval fixture 需要精心设计断言，不能只评价生成文本是否“看起来不错”。

## ADR-012：使用 Apache License 2.0

- **状态：** Accepted
- **决策：** LegacyPilot 使用 Apache License 2.0。
- **原因：** 它适合企业采用和开源协作，包含明确的专利授权与 NOTICE 机制。
- **后果：** 贡献默认按 Apache-2.0 授权；分发时保留 LICENSE、NOTICE 和必要归属信息。

## ADR-013：v0.1 默认使用 H2 File + Flyway

- **状态：** Accepted
- **决策：** v0.1 默认使用 H2 file mode 持久化 Project、Task、TaskRun 和状态转换，并由 Flyway 管理 Schema。
- **原因：** 基础生命周期无需 Docker 即可运行和恢复，降低首次体验门槛；Repository ports 仍保持数据库无关。
- **后果：** PostgreSQL Adapter 可在后续增加而不改变领域接口；迁移脚本避免依赖 H2 专有业务语义，并通过文件数据库重启测试。

## ADR-014：Maven 默认离线并只读挂载依赖缓存

- **状态：** Accepted
- **决策：** Maven 工具固定使用离线模式；宿主 Maven repository 仅以只读方式挂载到容器，依赖下载或缓存预热不属于 Agent 权限。
- **原因：** 构建时临时开放网络会扩大供应链与数据外传风险，也使运行结果难以复现。
- **后果：** 项目依赖未预热时命令会明确失败；后续若引入受控代理，必须作为新的策略能力单独设计和审计。

## ADR-015：JavaParser + Lucene BM25 构成代码智能基线

- **状态：** Accepted
- **决策：** JavaParser 负责 Java 21 AST 与确定性符号/依赖索引；Lucene BM25 与精确匹配是始终可用的检索基线，Vector Retriever 仅作为可选适配器。
- **原因：** Java 代码关系需要结构化证据，检索又必须在没有外部向量服务时可本地复现和评测。
- **后果：** 索引绑定 schema version 与 Git revision；未解析依赖显式保留；Hybrid 排名必须标明候选来源，Vector 故障不能中断基线检索。

## 待决问题

| 编号 | 问题 | 决定时间点 |
| --- | --- | --- |
| OQ-03 | 首个默认模型提供商与费用基线 | Issue 12 Model Gateway |
| OQ-04 | 向量检索是否纳入 v0.1 发布门槛 | Issue 08 基线完成后以 Eval 决定 |

# LegacyPilot v0.1 MVP Backlog

以下 20 项可以直接创建为 GitHub Issues。编号表示建议顺序，不代表必须完全串行。

## 里程碑

| 里程碑 | 目标 | Issues |
| --- | --- | --- |
| M0 Foundation | 工程、领域和交互骨架可运行 | 01-03 |
| M1 Safe Execution | 隔离工作区、沙箱和工具安全基线 | 04-05、10-11 |
| M2 Code Intelligence | Java 结构理解和上下文检索 | 06-09 |
| M3 Agent Loop | 模型、计划、执行、审批和验证闭环 | 12-16 |
| M4 Open Protocol & Evidence | MCP、Demo、Eval 和公开演示 | 17-20 |

## 统一 Definition of Done

每个 Issue 除自身验收条件外，还必须满足：

- 公共行为有单元或集成测试，关键安全边界有负向测试。
- 新增配置有安全默认值、校验和示例。
- 结构化日志不包含密钥或完整敏感源码。
- 对外接口或配置变化同步更新文档。
- CI 通过，且没有无解释的跳过测试。

---

## Issue 01 — 初始化 Java 21 Maven 多模块工程与开源治理

**Status：** ✅ Completed
**Labels：** `type:foundation`, `area:build`, `priority:P0`
**Milestone：** M0 Foundation
**Depends on：** 无

### 目标

建立可编译、可测试、可贡献的工程骨架，并决定许可证。

### 工作内容

- 创建根 `pom.xml`、基础模块和依赖版本管理。
- 配置格式化、编译、单测、JaCoCo 和基础静态检查。
- 创建 CI、Dependabot/依赖更新策略、PR/Issue 模板。
- 增加 LICENSE、NOTICE、CONTRIBUTING、SECURITY、CODE_OF_CONDUCT。
- 用架构测试禁止 domain 依赖 Spring 或 Adapter。

### 验收条件

- `./mvnw verify` 在干净环境成功。
- CI 在 Pull Request 上执行编译、测试和静态检查。
- 根 README 的本地开发命令有效。
- 许可证有明确结论，不再保留“待定”占位。

---

## Issue 02 — 定义 Task/Run/Plan/State 领域模型与持久化端口

**Status：** ✅ Completed
**Labels：** `type:feature`, `area:domain`, `priority:P0`
**Milestone：** M0 Foundation
**Depends on：** 01

### 目标

让 Agent 任务拥有明确、可恢复、与框架无关的状态模型。

### 工作内容

- 定义 Project、Task、TaskRun、Plan、Budget、Approval、Verification、Report。
- 实现状态机和合法转换规则。
- 定义 Repository ports 与事件序列。
- 选择并实现 v0.1 存储方案及迁移脚本。
- 定义乐观锁/版本号，避免并发重复执行。

### 验收条件

- 非法状态转换被领域层拒绝并有测试。
- Run 保存后可重载全部预算、步骤与终止原因。
- domain 模块无 Spring、数据库和模型厂商依赖。
- 并发更新同一 Run 时不会静默覆盖状态。

---

## Issue 03 — 实现 Project/Task 的 REST API 与 CLI 骨架

**Status：** ✅ Completed（生命周期骨架范围；审批、任务文件与报告随 Agent Runtime 交付）
**Labels：** `type:feature`, `area:api`, `area:cli`, `priority:P1`
**Milestone：** M0 Foundation
**Depends on：** 02

### 目标

提供无需 UI 即可完成项目注册、任务创建、状态查询和审批的交互面。

### 工作内容

- REST：注册项目、创建任务、启动 Run、查状态、查报告。
- CLI：`project add`、`task create`、`task run`、`task status`、`task approve`。
- 统一错误模型、关联 ID 和参数校验。
- 支持从 YAML/JSON 读取任务定义。

### 验收条件

- CLI 可以创建任务并轮询到一个模拟终态。
- OpenAPI/命令帮助与实际参数一致。
- 非法项目路径、空需求、错误预算返回明确错误。
- API 和 CLI 复用 application service，不重复业务逻辑。

---

## Issue 04 — 实现 Git 项目导入、固定 revision 与隔离 worktree

**Status：** ✅ Completed
**Labels：** `type:feature`, `area:workspace`, `security`, `priority:P0`
**Milestone：** M1 Safe Execution
**Depends on：** 01、02

### 目标

确保每个任务在可审计、可丢弃的独立工作区运行。

### 工作内容

- 支持本地 Git 仓库和公开 Git URL。
- 把分支解析为固定 commit，创建 task-specific worktree。
- 实现工作区状态、清理、保留和异常恢复。
- 规范化并验证所有路径位于 worktree 内。
- 检测 dirty base、submodule、LFS 等不支持情况并明确提示。

### 验收条件

- Agent 变更不会修改源工作区。
- `../`、绝对路径和符号链接逃逸全部被拒绝。
- 同一项目的两个 Run 使用互不影响的 worktree。
- 清理操作不会删除用户仓库或非本任务目录。

---

## Issue 05 — 实现 Docker Sandbox 与受限进程执行器

**Status：** ✅ Completed（真实容器 smoke test 在 Docker daemon 与固定镜像可用时运行）
**Labels：** `type:feature`, `area:sandbox`, `security`, `priority:P0`
**Milestone：** M1 Safe Execution
**Depends on：** 04

### 目标

让 Maven 和分析命令在资源、路径、用户和网络受控的环境中运行。

### 工作内容

- 非 root 容器、只读根文件系统和受控挂载。
- CPU、内存、进程数、磁盘、timeout 和输出上限。
- 默认无网络；设计依赖缓存/预热策略。
- 实现取消时进程树清理和容器回收。
- 结构化返回 exit code、stdout/stderr 摘要、耗时与超时原因。

### 验收条件

- 容器无法读取 worktree/允许缓存之外的宿主文件。
- 超时和取消会终止子进程，不残留容器。
- 资源限制可配置且有安全默认值。
- 恶意 Maven fixture 无法写出挂载边界或访问网络。

---

## Issue 06 — 使用 JavaParser 构建 Java AST 与符号索引

**Status：** ✅ Completed
**Labels：** `type:feature`, `area:code-intelligence`, `priority:P0`
**Milestone：** M2 Code Intelligence
**Depends on：** 01、04

### 目标

把 Java 源码转成可定位、可查询的结构化项目模型。

### 工作内容

- 提取 package、class/interface/enum、method、field、annotation、import。
- 记录文件和行列位置、签名、修饰符与 Javadoc 摘要。
- 识别 Spring Controller/Service/Repository/Entity/Configuration。
- 支持 Maven 多模块源码根和测试源码根。
- 为索引定义 schema version 和 Git revision 身份。

### 验收条件

- Banking Demo 的符号数量与 golden snapshot 一致。
- 可按全限定名、简单名、方法名和注解查询。
- 语法错误文件不会导致整个索引失败，并在结果中报告。
- 索引结果能准确回到源码位置。

---

## Issue 07 — 构建类型、引用和基础调用依赖图

**Status：** ✅ Completed
**Labels：** `type:feature`, `area:code-intelligence`, `priority:P0`
**Milestone：** M2 Code Intelligence
**Depends on：** 06

### 目标

让检索能沿 Controller → Service → Repository → Entity 等真实结构扩展上下文。

### 工作内容

- 建立 extends、implements、imports、field type、method call/reference 边。
- 建立 Spring 注入与常见分层角色关系。
- 提供 upstream/downstream、限定深度和路径查询。
- 边记录证据位置和解析置信度。
- 未解析符号作为显式节点/错误，不静默丢弃。

### 验收条件

- Demo 的转账调用链可以通过图查询得到。
- 查询支持最大深度、边类型和结果上限。
- 图中的每条高置信边可追溯到源码证据。
- 图构建可按 revision 重建，结果确定。

---

## Issue 08 — 实现精确符号、BM25 与可插拔向量检索

**Status：** ✅ Completed
**Labels：** `type:feature`, `area:retrieval`, `priority:P0`
**Milestone：** M2 Code Intelligence
**Depends on：** 06

### 目标

建立不依赖单一 Embedding 的多路候选召回。

### 工作内容

- 实现符号/错误码精确搜索和 Lucene BM25。
- 定义统一 `Retriever` 接口与候选证据格式。
- 提供可选 Vector Retriever Adapter；未配置时正常降级。
- 合并、去重并标记每个候选的召回来源。
- 建立小型检索 golden dataset。

### 验收条件

- 禁用向量检索后全部检索测试通过。
- 关键词、符号和自然语言需求均能返回相关候选。
- 每个结果包含文件、位置、分数、来源和摘要。
- 公开基线能比较 BM25 与 Hybrid 的 Recall@K。

---

## Issue 09 — 实现依赖图扩展、排序与 Token Budget Context Builder

**Status：** ✅ Completed
**Labels：** `type:feature`, `area:context`, `priority:P0`
**Milestone：** M2 Code Intelligence
**Depends on：** 07、08

### 目标

把检索候选转成模型可用、来源明确且不超预算的代码上下文。

### 工作内容

- 从召回节点按边类型与距离扩展上下游代码。
- 实现可配置评分、去重、多样性和分层保留策略。
- 按文件/符号边界切片，避免截断关键签名。
- Token 估算、预算 packing 和超限摘要。
- 记录入选/淘汰原因用于 Trace 与 Eval。

### 验收条件

- 任意输入都不超过配置的 Context Token 预算。
- 转账需求上下文包含 Controller、Service、Repository 与相关测试。
- 输出带稳定引用 ID，可映射回代码索引。
- 单测覆盖重复候选、超预算、空结果和图爆炸。

---

## Issue 10 — 定义 Tool SPI、风险等级、Schema 与 Execution Policy

**Status：** ✅ Completed
**Labels：** `type:feature`, `area:tools`, `security`, `priority:P0`
**Milestone：** M1 Safe Execution
**Depends on：** 02

### 目标

统一工具的发现、校验、授权、执行和审计语义。

### 工作内容

- 定义 AgentTool、ToolDescriptor、ToolContext、ToolResult、ToolError。
- 定义输入/输出 JSON Schema、幂等性、timeout、风险等级。
- 实现 ToolRegistry 和 Policy Engine。
- 定义 allow/deny/approval 决策及 action digest。
- 对输入大小、输出大小和敏感字段做统一处理。

### 验收条件

- 未注册工具、Schema 错误、策略拒绝都有不同错误类型。
- 风险等级能触发预期的允许、拒绝或审批。
- action 输入变化会导致旧批准失效。
- 工具契约测试可被所有具体工具复用。

---

## Issue 11 — 实现 File、Git、Patch 与 Maven 工具集

**Status：** ✅ Completed
**Labels：** `type:feature`, `area:tools`, `priority:P0`
**Milestone：** M1 Safe Execution
**Depends on：** 04、05、10

### 目标

提供完成 MVP 所需的最小受控工具，而不暴露任意 shell。

### 工作内容

- `read_file`、`search_code`、`find_references`。
- `create_patch`/`apply_patch`，包含上下文匹配和冲突结果。
- `git_diff` 和变更统计。
- `compile_project`、`run_tests`、`run_test_class`、`static_analysis`。
- Maven Goal/Profile/Property 白名单和参数数组构造。

### 验收条件

- 每个工具通过统一契约测试与路径安全测试。
- 补丁只能修改配置允许的文件，并能报告冲突而非覆盖。
- Maven 工具在 Docker Sandbox 中运行且有 timeout。
- 不存在接受任意 shell 字符串的公共工具。

---

## Issue 12 — 实现厂商无关 Model Gateway 与 Spring AI Adapter

**Status：** ✅ Completed
**Labels：** `type:feature`, `area:model`, `priority:P0`
**Milestone：** M3 Agent Loop
**Depends on：** 01、02

### 目标

支持结构化模型输出、Usage 记录和厂商替换，同时不污染核心 Runtime。

### 工作内容

- 定义 ModelGateway、ModelRequest、ModelResult、Usage 和错误分类。
- 用 Spring AI 实现首个 Chat Model Adapter。
- 支持 JSON Schema/Structured Output 校验和有限格式纠正。
- 配置模型、温度、超时和费用表；密钥只从安全配置读取。
- 提供 Fake/Replay Model 供确定性测试。

### 验收条件

- Runtime 测试无需真实模型或网络。
- 格式错误、限流、超时和认证错误能被区分。
- Token 与估算成本进入结构化 Usage。
- 日志、异常和报告不泄露 API key。

---

## Issue 13 — 实现 Planner 与 Plan→Act→Observe→Verify Agent Loop

**Status：** ✅ Completed
**Labels：** `type:feature`, `area:runtime`, `priority:P0`
**Milestone：** M3 Agent Loop
**Depends on：** 02、09、10、12

### 目标

完成项目核心的自研 Agent Runtime，并能用 Fake Model 确定性跑通。

### 工作内容

- 结构化影响分析与 Change Plan Schema。
- 实现动作选择、工具执行、观察、计划修订和终止判定。
- 实现步骤、重试、Token、成本和时长预算。
- 每步 checkpoint 与恢复入口。
- 防止连续重复同一失败动作，检测无进展循环。

### 验收条件

- Fake Model 场景能完整跑通成功、失败、预算耗尽和暂停。
- 模型不能直接设置 `SUCCEEDED`。
- 每个状态转换有事件和持久化 checkpoint。
- 相同失败动作超过阈值后终止或要求人工处理。

---

## Issue 14 — 实现 Plan/Action 审批门与恢复执行

**Status：** ✅ Completed
**Labels：** `type:feature`, `area:approval`, `security`, `priority:P0`
**Milestone：** M3 Agent Loop
**Depends on：** 03、10、13

### 目标

在不牺牲审计性的前提下让用户控制写入和命令执行。

### 工作内容

- 展示计划、预计文件、动作、风险和原因。
- 支持 approve once、approve matching plan、deny。
- Approval 与 action digest、Run、actor 和过期时间绑定。
- CLI/API 提交审批并恢复 Run。
- 计划或输入变化后自动作废不再匹配的批准。

### 验收条件

- 未批准的受限动作不会执行。
- 篡改动作输入后旧批准不能复用。
- deny 后 Run 进入明确状态并生成报告。
- 服务重启后仍能识别待审批 Run 并继续。

---

## Issue 15 — 实现 Verification Pipeline 与修复反馈

**Status：** ✅ Completed
**Labels：** `type:feature`, `area:verification`, `priority:P0`
**Milestone：** M3 Agent Loop
**Depends on：** 05、11、13

### 目标

用确定性证据决定任务是否成功，并把可修复失败反馈给 Agent。

### 工作内容

- Workspace integrity、diff、compile、test、static analysis checks。
- required/optional check 配置与统一 evidence schema。
- 解析 Maven/Surefire/JaCoCo/静态分析结果。
- 生成精简失败上下文供 Agent 修复。
- 风险评级考虑变更规模、关键组件和验证缺口。

### 验收条件

- required check 失败时 Run 不可成功。
- 编译失败可在预算内回到执行阶段，成功后再次完整验证。
- 报告保存命令、退出码、关键摘要和产物引用。
- 变更受保护文件或超出阈值会被阻断/提升风险。

---

## Issue 16 — 实现 Agent Trace、指标、脱敏和 Markdown/JSON 报告

**Status：** ✅ Completed
**Labels：** `type:feature`, `area:observability`, `priority:P0`
**Milestone：** M3 Agent Loop
**Depends on：** 02、12、13、15

### 目标

让一次 Agent 运行可解释、可审计，并适合作为 GitHub 作品证据。

### 工作内容

- 定义 Trace Event schema 与 Task/Run/Step/Tool 关联。
- 记录状态、工具、模型 Usage、审批和验证 evidence。
- 实现密钥、Header、连接串和自定义规则脱敏。
- 输出 Markdown 与 JSON 报告。
- 暴露 Micrometer 指标和 OpenTelemetry Trace 基础接入。

### 验收条件

- 从报告可定位每个修改的计划来源和验证证据。
- Token、估算成本、时长、步骤和 Tool failure 可统计。
- 敏感 fixture 不会出现在日志、Trace 或报告。
- 大型工具输出被截断或外部引用，不撑爆数据库。

---

## Issue 17 — 实现最小 Java Project / Maven MCP Server

**Status：** ✅ Completed
**Labels：** `type:feature`, `area:mcp`, `priority:P1`
**Milestone：** M4 Open Protocol & Evidence
**Depends on：** 09、10、11、16

### 目标

让兼容 MCP 的客户端复用 LegacyPilot 的受控代码检索与验证能力。

### 工作内容

- 复用 Tool Registry、Workspace 和 Execution Policy。
- 暴露 search/read/find references/git diff/compile/test。
- MCP 会话绑定固定 Project/Workspace，拒绝任意宿主路径。
- 提供配置示例与本地客户端 smoke test。
- v0.1 默认不暴露写入类工具。

### 验收条件

- 客户端可以发现并调用全部允许工具。
- MCP 与内部调用产生相同 Trace 和策略判定。
- 通过参数注入访问其他路径会被拒绝。
- 关闭 MCP 不影响主 Agent Runtime。

---

## Issue 18 — 创建 Banking Demo 与第一条每日转账限额任务

**Status：** ✅ Completed
**Labels：** `type:demo`, `area:samples`, `priority:P0`
**Milestone：** M4 Open Protocol & Evidence
**Depends on：** 01

### 目标

提供规模适中、业务关系真实、可确定性验收的公开 Spring Boot fixture。

### 工作内容

- 建立 Account、Customer、Transfer、Transaction 等领域。
- 包含 Controller/Service/Repository、数据库迁移和测试。
- 初始版本故意不包含每日限额。
- 定义需求、预期受影响组件和确定性 assertions。
- 固定 fixture commit，避免 Eval 随主线漂移。

### 验收条件

- Demo 基线 `mvn test` 通过。
- 手工实现参考解存在但不进入 Agent 可见上下文。
- 任务断言覆盖普通/VIP/超限/跨日/并发或原子性关键场景。
- 影响文件和调用链有 golden reference。

---

## Issue 19 — 构建 5 个任务的 Eval Runner、指标和基线

**Status：** ✅ Completed
**Labels：** `type:feature`, `area:evaluation`, `priority:P0`
**Milestone：** M4 Open Protocol & Evidence
**Depends on：** 15、16、18

### 目标

用公开、可重复的数据证明 Agent 的任务完成能力，而不是只展示一次成功录屏。

### 工作内容

- 定义 task/assertion/result schema 和版本化 dataset。
- 设计 5 个任务：新增枚举、校验规则、缺陷修复、查询字段、转账限额。
- 实现 Runner、隔离重置、并发控制和结果聚合。
- 统计成功率、检索 Recall、编译/测试、步骤、Token、成本、时长。
- 输出环境、模型、Prompt/策略版本和公开基线。

### 验收条件

- 单命令可从固定 fixture 运行全部任务。
- 同一 Run 的产物、Trace 和报告可追溯。
- 确定性断言不依赖 LLM judge。
- v0.1 候选版本达到至少 4/5 端到端成功，未达到时如实报告。

---

## Issue 20 — 完成端到端发布验收、文档、Demo 与 v0.1.0

**Status：** ✅ Completed（`v0.1.0`）
**Labels：** `type:release`, `area:docs`, `priority:P0`
**Milestone：** M4 Open Protocol & Evidence
**Depends on：** 03-19

### 目标

把工程能力整理成新用户可复现、招聘方一眼可验证的开源发布物。

### 工作内容

- 编写 Quickstart、配置、模型提供商、Docker 和故障排查文档。
- 录制每日转账限额任务的 GIF/短视频。
- README 第一屏展示影响分析、修改、测试和风险结果。
- 运行完整 Eval 并提交基线报告与已知限制。
- 执行安全、许可证、依赖和发布清单，创建 `v0.1.0` tag。

### 验收条件

- 新环境按 Quickstart 30 分钟内完成 smoke task。
- 演示从空索引开始，不使用预制补丁。
- README 链接、命令、示例报告和架构图有效。
- 发布说明包含指标、模型/费用环境、限制和后续路线。
- PRD 中全部 v0.1 发布物均有对应实现或明确延期说明。

---

## 建议的首批并行顺序

```text
01
├── 02 -> 03
├── 04 -> 05
├── 06 -> 07
│        └── 09
├── 08 ─────┘
├── 10 -> 11
├── 12
└── 18

02 + 09 + 10 + 12 -> 13 -> 14/15 -> 16
09 + 10 + 11 + 16 -> 17
15 + 16 + 18 -> 19
03-19 -> 20
```

一个维护者开发时，建议优先打通 `01 → 02 → 04 → 06 → 08 → 10 → 11 → 12 → 13 → 15 → 16 → 18 → 19 → 20` 主链，再补足图扩展、审批、MCP 和其他增强项。

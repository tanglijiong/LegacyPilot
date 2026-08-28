# LegacyPilot 技术架构

## 1. 架构原则

1. **Single Agent + Strong Harness**：优先提升单 Agent 的状态、工具、验证与恢复能力。
2. **验证先于完成**：模型声称完成不等于任务完成，验证器才拥有终态判定权。
3. **默认拒绝**：文件、命令、网络和凭证访问均遵循最小权限。
4. **结构检索优先**：Java AST、符号和依赖图是基础，向量检索是增强项。
5. **过程可重放**：每个状态转换和工具调用均有可审计事件。
6. **厂商可替换**：核心 Runtime 不直接依赖任何具体 LLM 或向量数据库。
7. **先模块化单体**：v0.1 在一个仓库和部署单元内保持清晰边界，避免微服务成本。

## 2. 系统上下文

```text
Developer / Reviewer
        |
        | CLI / REST
        v
LegacyPilot Server
        |
        +-- Model Provider (configured outbound access)
        +-- Task Store / Index Store
        +-- Git repository + isolated worktree
        +-- Docker Sandbox
        +-- MCP clients (optional inbound, restricted tools)
        +-- Trace / Metrics backend
```

v0.1 主要交互面是 CLI 和 REST API。Dashboard 延后到 v0.3，避免 UI 与核心执行链竞争开发资源。

## 3. 逻辑组件

```text
┌─────────────────────────────────────────────────────────────┐
│                    CLI / REST API                            │
├─────────────────────────────────────────────────────────────┤
│ Task Service │ Approval Service │ Report Service            │
├─────────────────────────────────────────────────────────────┤
│                     Agent Runtime                           │
│ Planner │ State Machine │ Budget │ Context │ Evaluator      │
├──────────────────────┬──────────────────────────────────────┤
│ Code Context Engine  │ Tool Runtime                         │
│ AST / Symbols        │ Registry / Policy / Executor         │
│ BM25 / Vector        │ File / Git / Maven / Patch           │
│ Dependency Graph     │ MCP adapters                         │
├──────────────────────┴──────────────────────────────────────┤
│ Workspace / Sandbox │ Persistence │ Trace / Metrics         │
└─────────────────────────────────────────────────────────────┘
```

### 3.1 Agent Runtime

Runtime 是项目的核心价值，不由 Spring AI 的一次 `prompt().tools().call()` 代替。

主要抽象：

- `Task`：用户目标、约束、验收条件和目标仓库快照。
- `AgentState`：当前阶段、步骤、预算、已知事实、错误和终止原因。
- `AgentAction`：模型建议的结构化下一步动作。
- `AgentContext`：本步允许模型看到的最小相关上下文。
- `TaskPlanner`：生成和修订变更计划。
- `ContextManager`：检索、排序、压缩并记录上下文来源。
- `ToolRegistry` / `ToolExecutor`：发现和执行受控工具。
- `ExecutionPolicy`：决定允许、拒绝或请求审批。
- `Evaluator`：判断工具结果和任务验证结果。
- `BudgetGuard`：限制步骤、重试、Token、成本和时长。
- `AgentTrace`：记录事件，不把状态散落在日志文本中。

Runtime 伪代码：

```java
while (!state.isTerminal()) {
    budgetGuard.check(state);
    AgentContext context = contextManager.build(state);
    AgentAction action = model.nextAction(context);
    PolicyDecision decision = executionPolicy.evaluate(action, state);

    if (decision.requiresApproval()) {
        state.pauseForApproval(action);
        break;
    }

    ToolResult result = toolExecutor.execute(action);
    Evaluation evaluation = evaluator.evaluate(state, action, result);
    state = state.transition(action, result, evaluation);
    stateRepository.save(state);
}
```

### 3.2 Code Context Engine

索引管线：

```text
Java source
  -> JavaParser AST
  -> Symbol / Type / Spring-role extraction
  -> Reference and dependency edges
  -> BM25 document index
  -> Optional embeddings
  -> Project index snapshot
```

检索管线：

```text
Requirement
  -> entities, symbols, error codes, domain terms
  -> exact symbol + BM25 + optional vector candidates
  -> dependency graph expansion
  -> rank, deduplicate, diversity control
  -> token-budget packing
  -> context with source and selection reason
```

建议的候选评分仅作为初始基线：

```text
score = 0.35 * symbol
      + 0.25 * bm25
      + 0.20 * graph_proximity
      + 0.15 * vector
      + 0.05 * git_recency
```

权重必须可配置，并通过 Eval 调整。向量检索不可成为核心流程的单点依赖。

当前实现位于 `java-analyzer` 与 `context-engine`：JavaParser 生成带 revision/schema 身份的确定性索引；依赖边同时保存源码证据、置信度和未解析目标；Lucene 在内存索引中使用 BM25。Hybrid Retriever 对各路结果归一化后按可配置权重合并，Vector 未配置或失败时返回空候选，不影响 Exact/BM25。Context Builder 以符号为最小切片，沿依赖图双向扩展，并在严格 Token 预算内记录入选、摘要和淘汰原因。

### 3.3 Tool Runtime

工具契约至少包含：

```java
public interface AgentTool<I, O> {
    ToolDescriptor descriptor();
    ToolResult<O> execute(ToolContext context, I input);
}
```

`ToolDescriptor` 描述名称、用途、JSON Schema、风险等级、是否幂等、默认超时和最大输出。工具输出需区分：成功、业务失败、策略拒绝、超时、系统错误。

风险等级：

| 等级 | 示例 | v0.1 默认策略 |
| --- | --- | --- |
| `READ_ONLY` | 读文件、搜索、查看 diff | 自动允许 |
| `WORKSPACE_WRITE` | 应用补丁、创建测试 | 首次计划批准后允许 |
| `COMMAND_EXECUTION` | Maven compile/test | 命令与参数命中白名单才允许 |
| `EXTERNAL_IO` | 网络、推送、发消息 | 默认拒绝 |

工具不能接受任意 shell 字符串。Maven 工具应构造参数数组，并限制 Goal、Profile、属性和工作目录。

### 3.4 Verification Engine

验证是独立组件，不服从模型的主观判断。标准流水线：

1. `WorkspaceIntegrityCheck`：确认无 worktree 外写入。
2. `DiffCheck`：限制文件类型、变更规模和敏感文件。
3. `CompileCheck`：执行配置的 Maven compile 阶段。
4. `TestCheck`：运行目标测试和完整测试集。
5. `StaticAnalysisCheck`：运行项目已有的 Checkstyle/SpotBugs 等规则。
6. `CoverageCheck`：项目支持时采集 JaCoCo 结果。
7. `RequirementCheck`：以确定性断言优先，LLM Review 仅作补充。
8. `RiskAssessment`：根据变更范围、关键组件和验证缺口评级。

终态规则：

- 所有 required checks 通过：`SUCCEEDED`。
- 存在可修复失败且预算充足：回到 `EXECUTING`。
- 必需检查失败且预算耗尽：`FAILED`。
- 只有可选检查缺失：可成功，但报告标记 `WARNINGS`。

## 4. 任务状态机

```text
CREATED
  -> PREPARING_WORKSPACE
  -> INDEXING
  -> PLANNING
  -> WAITING_FOR_APPROVAL (optional)
  -> EXECUTING
  -> VERIFYING
      -> EXECUTING (repair within budget)
      -> SUCCEEDED
      -> FAILED

Any non-terminal state -> CANCELLED
Recoverable process loss -> RECOVERING -> previous stable state
```

状态转换必须由领域命令触发并产生事件，不能靠修改数据库状态字段绕过约束。

## 5. 关键数据模型

| 实体 | 关键字段 |
| --- | --- |
| `Project` | id、repoUri、baseRevision、buildType、languageVersion |
| `ProjectIndex` | projectId、revision、schemaVersion、createdAt |
| `Task` | id、projectId、requirement、constraints、acceptanceCriteria |
| `TaskRun` | id、taskId、modelConfig、budgets、status、workspaceId |
| `Plan` | version、steps、affectedComponents、expectedFiles、risk |
| `ToolInvocation` | tool、inputDigest、risk、decision、timing、result |
| `Approval` | actionDigest、decision、actor、reason、expiresAt |
| `VerificationRun` | checks、evidence、status、duration |
| `TraceEvent` | sequence、type、timestamp、payload、redactionVersion |
| `Report` | summary、diffStats、evidence、risk、openIssues |

索引数据与任务运行数据分开版本化。代码索引以 Git revision 为身份，避免任务运行时读取到漂移的源码。

## 6. 工作区与沙箱

### 6.1 双层隔离

- **Git worktree**：隔离用户原工作区，提供清晰 diff 和可丢弃性。
- **Docker sandbox**：隔离构建进程、文件系统、用户权限和网络。

### 6.2 默认约束

- 容器以非 root 用户运行。
- 根文件系统只读，仅挂载任务 worktree、Maven 缓存和临时目录。
- 任务 worktree 可写，其他挂载只读。
- 默认无网络；依赖下载使用显式预热或受控代理策略。
- CPU、内存、进程数、磁盘和执行时间均有限制。
- 任务结束后清理容器；worktree 保留到用户接受或丢弃。

### 6.3 路径防护

所有路径必须规范化后验证位于 worktree 根目录内，并拒绝通过 `..`、绝对路径、符号链接或硬链接逃逸。测试必须覆盖这些边界。

## 7. 审批模型

审批针对“动作摘要”而不是模糊的整场会话：

- 显示将调用的工具、目标文件或命令、风险等级和原因。
- 通过 action digest 绑定具体输入；动作改变后原批准失效。
- 支持 approve once、approve matching plan 和 deny。
- v0.1 不提供“永久允许任意命令”。

## 8. LLM 与上下文边界

核心定义一个与厂商无关的 `ModelGateway`：

```java
public interface ModelGateway {
    <T> ModelResult<T> generate(ModelRequest request, Class<T> schema);
}
```

Structured Output 必须通过 Schema 校验。修复格式错误的重试与业务重试分开计数。模型输入记录摘要、模板版本与上下文引用，不默认持久化完整源码。

## 9. MCP 边界

v0.1 提供最小 MCP Server，复用 Tool Runtime，不另写一套执行逻辑。

首批工具：

- `project.search_code`
- `project.find_references`
- `project.read_file`
- `maven.compile_project`
- `maven.run_tests`
- `git.diff`

MCP 会话必须映射到固定 project/workspace，不能由客户端传入任意宿主路径。写入类 MCP 工具延后或默认关闭。

## 10. Observability 与审计

### Trace

- Task / Run / Step / Tool invocation 使用关联 ID。
- 记录状态转换、策略判定、审批、模型调用摘要和验证证据。
- 大输出存对象引用或截断摘要，避免日志爆炸。

### Metrics

- `task_success_rate`
- `task_duration_seconds`
- `agent_steps_total`
- `tool_invocations_total{tool,status}`
- `tool_duration_seconds{tool}`
- `llm_tokens_total{direction,model}`
- `llm_estimated_cost`
- `verification_pass_rate{check}`
- `retrieval_candidates_total{source}`

### 脱敏

集中式 Redactor 处理密钥、Authorization Header、连接串和用户配置的敏感正则。Trace Schema 带脱敏版本，便于后续审计。

## 11. 错误处理与恢复

- 每个工具有 timeout、output limit、错误分类和 retry policy。
- Runtime 只自动重试明确可恢复错误，如临时模型超时；编译失败进入分析/修复循环。
- 每一步后保存 checkpoint；恢复时先确认 worktree、容器和 Git revision 是否一致。
- 不确定某动作是否已执行时，不盲目重放写操作，进入人工检查状态。

v0.2 第一阶段实现版本化 durable-state envelope、Action Journal、带 epoch fencing 的 Run Lease、append-only File Trace 和带 TTL 的 Task Memory。Runtime 在每次 checkpoint 前续租并校验 epoch；`SUCCEEDED` action 直接复用持久结果，`RUNNING` 的条件/非幂等 action 转入 `NEEDS_REVIEW`。Recovery Coordinator 只自动恢复没有审批或人工审查阻塞的状态。详细运维与磁盘布局见 [`RESILIENT_HARNESS.md`](RESILIENT_HARNESS.md)。

## 12. 测试策略

- **Unit**：状态机、预算、策略、路径校验、排名和报告生成。
- **Contract**：每个 Tool 的 Schema、错误语义和超时行为。
- **Integration**：真实 Maven 示例项目、Git worktree、Docker sandbox。
- **Golden**：固定索引输入对应稳定符号/依赖输出。
- **Eval**：端到端业务任务，比较任务结果而非措辞。
- **Security**：路径穿越、命令注入、符号链接、日志泄密和资源耗尽。

## 13. 部署形态

v0.1 使用 Docker Compose：Server、PostgreSQL（可选 pgvector）和观测组件。CLI 可以以本地进程连接 Server，也可启用单进程开发模式。

生产级多租户、远程沙箱集群和 Kubernetes 不属于 v0.1。

## 14. 端到端任务时序

```text
User -> API: create task(requirement)
API -> Workspace: create worktree(baseRevision)
API -> Context Engine: index(worktree)
Runtime -> Context Engine: retrieve(requirement)
Runtime -> Model: create structured plan
Runtime -> User: request approval(plan + actions)
User -> Runtime: approve(actionDigest)
Runtime -> Tools: search/read/apply patch
Runtime -> Verification: compile/test/static analysis
Verification -> Runtime: evidence
Runtime -> Model: repair plan (only if failed and budget remains)
Runtime -> Report: render JSON + Markdown
Report -> User: result, evidence, risk, diff
```

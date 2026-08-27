# 下一长期任务：任务生命周期基础闭环

**覆盖 Issues：** 02、03、04
**建议执行时间：** 1–2 个完整开发日
**目标里程碑：** 完成 M0 Foundation，并建立 M1 Safe Execution 的工作区基础
**状态：** ✅ Completed（2026-08-27）

## 实施结果

Issues 02–04 的基础闭环已经落地：纯 Java 领域状态机、H2/Flyway 持久化与乐观锁、共享 Application Use Cases、REST/Problem Details/OpenAPI、JSON CLI，以及固定 revision 的受管 Git worktree。

验收证据：

- Java 21 下 `./mvnw verify` 全模块通过。
- 领域、应用、持久化、workspace、REST、CLI 测试通过 80% 行覆盖率门禁。
- 真实 Git fixture 验证双 Run 隔离、revision 固定、源仓库不受影响、marker 所有权校验与安全清理。
- H2 file 数据在重新创建数据源后可重载，Flyway 重复迁移无新增变更。
- REST 端到端覆盖注册、建任务、启动、查询完整历史、取消；响应透传或生成 correlation ID。
- 打包后的 CLI 已通过独立 JVM 进程 smoke test。

本轮按既定边界只推进到 `WORKSPACE_READY`。审批、任务 YAML 文件、报告和模型动作将在 Agent Runtime 相关 Issue 中实现。

## 1. 任务目标

在不接入真实 LLM、不执行 Maven 沙箱命令的前提下，实现 LegacyPilot 第一条确定性的任务生命周期：

```text
注册 Git 项目
  -> 固定 base commit
  -> 创建 Task
  -> 创建 TaskRun
  -> 创建隔离 Git worktree
  -> 按合法状态机推进
  -> 持久化并查询状态
  -> 服务重启后恢复 Run
  -> 安全清理或保留 worktree
```

完成后，后续 Agent Runtime、Tool Runtime 和 Sandbox 都能建立在稳定的任务、状态与工作区边界上。

## 2. 本轮明确决策

### 持久化

- v0.1 首个实现使用 H2 file mode，保证无 Docker 环境也能运行。
- 使用 Flyway 管理 Schema，迁移 SQL 保持 PostgreSQL 兼容。
- 领域层只定义 Repository ports，不依赖 JPA、Spring Data 或 H2。
- PostgreSQL Adapter 与 Docker Compose 在后续任务增加，不改变领域接口。

### Git 操作

- 使用系统 Git CLI，通过参数数组执行，不拼接 shell 字符串。
- 所有命令设置明确工作目录、超时、输出上限和错误类型。
- worktree 根目录使用应用管理的专用目录，不允许调用方传入任意目标路径。

### 交互面

- REST API 使用 Spring Boot。
- CLI 使用 Picocli，并调用同一 Application Service；禁止复制业务逻辑。
- 本轮 API/CLI 只管理生命周期，不调用模型、不修改业务源码。

## 3. 阶段 A：Issue 02 — 领域模型与持久化

### 领域模型

实现：

- `Project`、`ProjectId`、`RepositoryLocation`、`GitRevision`
- `Task`、`TaskId`、`Requirement`、`AcceptanceCriteria`
- `TaskRun`、`RunId`、`RunStatus`、`RunVersion`
- `Plan`、`Budget`、`Approval`、`VerificationResult`
- `WorkspaceId`、`WorkspaceState`
- 领域事件与明确的终止原因

首个状态机：

```text
CREATED
  -> PREPARING_WORKSPACE
  -> WORKSPACE_READY
  -> PLANNING
  -> WAITING_FOR_APPROVAL
  -> EXECUTING
  -> VERIFYING
  -> SUCCEEDED | FAILED

任意非终态 -> CANCELLED
可恢复异常 -> RECOVERING -> 最近稳定状态
```

本轮只真正执行到 `WORKSPACE_READY`；后续状态仍需建模和测试，供 Agent Runtime 使用。

### 持久化实现

- 定义 `ProjectRepository`、`TaskRepository`、`TaskRunRepository` ports。
- 增加 H2 file adapter 和 Flyway migrations。
- 使用显式映射隔离领域模型与数据库实体。
- 使用版本字段实现乐观锁。
- 领域状态转换和事件写入必须位于同一事务。

### 阶段验收

- 所有合法和非法状态转换都有参数化测试。
- 保存、重载后对象语义保持一致。
- 两个并发更新不能静默覆盖彼此。
- domain 模块继续通过架构约束，不出现 Spring/JPA/Jackson 类型。
- migration 可在全新数据库和已有数据库上重复执行。

## 4. 阶段 B：Issue 03 — REST API 与 CLI

### Application Services

- `RegisterProjectUseCase`
- `CreateTaskUseCase`
- `StartRunUseCase`
- `GetRunStatusUseCase`
- `CancelRunUseCase`
- `ApproveRunActionUseCase` 接口占位，但不实现 Agent 动作

### REST API

建议端点：

```text
POST   /api/v1/projects
GET    /api/v1/projects/{projectId}
POST   /api/v1/projects/{projectId}/tasks
GET    /api/v1/tasks/{taskId}
POST   /api/v1/tasks/{taskId}/runs
GET    /api/v1/runs/{runId}
POST   /api/v1/runs/{runId}/cancel
```

要求：

- 请求、响应 DTO 与领域模型分离。
- 使用统一 Problem Details 错误响应。
- 校验空需求、非法路径、未知 ID、重复操作和版本冲突。
- 生成并透传 request/run correlation ID。

### CLI

建议命令：

```text
legacy-pilot project add --path <repo>
legacy-pilot task create --project <id> --file task.yml
legacy-pilot task run --task <id>
legacy-pilot task status --run <id>
legacy-pilot task cancel --run <id>
```

### 阶段验收

- CLI 和 REST 调用同一组 Use Cases。
- OpenAPI、CLI help 和真实参数一致。
- API 集成测试覆盖成功、校验失败、冲突和不存在资源。
- CLI 可以用 JSON 输出，便于后续脚本和 Eval Runner 调用。

## 5. 阶段 C：Issue 04 — Git 项目与 Worktree

### Workspace Service

实现：

- 本地 Git 仓库注册和检查。
- 公开 Git URL clone 到应用管理的 repository cache。
- 把 branch/tag 解析并固定为 commit SHA。
- 为每个 Run 创建独立 worktree。
- 查询 worktree 状态、diff 和当前 commit。
- 标记保留、正常清理和异常恢复。

### 安全边界

- 规范化路径后确认位于受管根目录。
- 拒绝 `..`、任意绝对目标路径和符号链接逃逸。
- 清理只能作用于数据库中登记且拥有匹配 marker 的 workspace。
- 不修改、不 reset、不 clean 用户原仓库。
- Git 命令必须使用参数数组、timeout 和输出上限。
- 本轮不支持 submodule、Git LFS 和带凭证的私有 URL；返回明确错误。

### 阶段验收

- 两个并发 Run 获得不同 worktree。
- 修改 worktree 后源仓库保持不变。
- Run 固定到创建时的 commit，源分支后续移动不影响它。
- 路径穿越、符号链接逃逸和伪造 workspace marker 测试全部失败关闭。
- 服务重启后能发现并恢复已登记 worktree。
- 清理一个 Run 不影响其他 Run 或源仓库。

## 6. 端到端验收场景

使用测试 fixture 仓库执行：

1. 通过 CLI 注册本地 Git 项目。
2. 创建一条“示例需求” Task。
3. 启动 Run，状态从 `CREATED` 推进到 `WORKSPACE_READY`。
4. API 查询返回固定 commit、workspace ID 和完整状态历史。
5. 手工修改该 worktree 中的文件，确认源仓库没有变化。
6. 重启应用，确认 Run 和 worktree 仍可查询。
7. 取消 Run 并安全清理 worktree。
8. 重复运行相同步骤，确认无残留状态和目录冲突。

## 7. 质量门槛

- `./mvnw verify` 全部通过。
- 新增核心领域代码行覆盖率不低于 90%。
- 状态机、乐观锁、路径和清理逻辑必须有负向测试。
- 至少一个 REST 集成测试和一个真实进程级 CLI smoke test。
- 不引入通用 `runShell(String)` API。
- 不记录本地绝对路径之外的敏感内容，不记录 Git URL 凭证。
- 更新 README、OpenAPI 示例和对应 ADR。

## 8. 建议提交切片

1. `feat(domain): add task lifecycle model and state machine`
2. `feat(persistence): persist projects tasks and runs with flyway`
3. `feat(api): expose project and task lifecycle endpoints`
4. `feat(cli): add project and task lifecycle commands`
5. `feat(workspace): create isolated git worktrees per run`
6. `test(workspace): cover traversal concurrency and recovery`
7. `docs: document lifecycle API CLI and persistence decisions`

## 9. 暂停条件

只有出现以下情况才暂停并请求用户决策：

- 需要改变已确定的 worktree 隔离安全边界。
- H2 与 PostgreSQL 兼容目标发生无法规避的 Schema 冲突。
- 必须安装或修改用户系统级软件才能继续。
- 发现现有未提交修改与任务目标直接冲突且无法安全合并。

普通测试失败、依赖版本冲突、格式问题和实现细节不属于暂停条件，应继续诊断和修复。

## 10. 完成后的下一任务

下一长期任务建议合并 Issue 05、10、11：Docker Sandbox + Tool SPI + File/Git/Maven 受控工具集。届时系统将从“安全管理任务工作区”升级到“安全执行 Agent 工具”。

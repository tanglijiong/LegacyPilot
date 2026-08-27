# 长期任务：Model Gateway、Agent Loop、审批与验证

**覆盖 Issues：** 12、13、14、15、16
**状态：** Completed
**开始日期：** 2026-08-27
**完成日期：** 2026-08-27

## 目标

将固定 revision 工作区、代码上下文、受控工具和领域状态连接为可暂停、可恢复、可验证的单 Agent 执行闭环。模型只能提出结构化计划和动作，策略与 Verification Pipeline 决定动作能否执行及 Run 能否成功。

## 实现切片

1. `model-spi`：厂商无关请求/响应、Structured Output、Usage、错误分类、Fake/Replay。
2. `model-spring-ai`：首个 Spring AI ChatModel Adapter，外部密钥仅由宿主配置管理。
3. `verification`：workspace/diff/compile/test/static-analysis checks、修复反馈与风险评级。
4. `observability`：Trace schema、脱敏、聚合指标、Markdown/JSON 报告。
5. `agent-runtime`：Planner、Plan→Act→Observe→Verify、预算、checkpoint、循环检测。
6. action digest 审批：approve once、matching plan、deny、过期与持久化恢复。
7. REST/CLI：提交审批、继续执行、读取报告。

## 已交付能力

- 厂商无关 `ModelGateway`、结构化输出校验、最多三次格式纠正、Fake/Replay 和 Spring AI `ChatModel` Adapter。
- Plan→Act→Observe→Verify 单 Agent Loop，包含步骤、重试、Token、成本、时长预算以及重复失败熔断。
- Checkpoint、运行请求和审批记录的原子文件持久化；服务重启后可按 `runId` 恢复。
- `approve once`、`matching plan`、deny、过期时间和 action/plan digest 精确绑定。
- workspace integrity、diff policy、compile、test、static analysis 验证流水线，并将可修复失败反馈给 Planner。
- 脱敏 Trace、Micrometer 指标、Markdown/JSON 报告及报告文件持久化。
- REST `agent-runs` 执行/查询/审批/恢复/报告接口，以及 CLI `agent-approve`、`agent-resume` 命令。

## 验收结果

- `spotless:apply clean verify` 全仓通过：编译、单元测试、集成测试、Checkstyle、SpotBugs、JaCoCo 均通过。
- 共 76 个测试，0 failure、0 error；Docker 不可用环境中的真实容器测试按既有条件跳过 1 个。
- 新增核心模块行覆盖率：`model-spi` 91.24%、`verification` 89.25%、`agent-runtime` 82.48%、`observability` 92.13%。
- 端到端测试覆盖模型不能直接成功、审批暂停与重启恢复、一次性审批消费、deny、预算耗尽和 required verification 成功判定。

## 不变量

- 模型输出必须通过 Schema 和类型校验，模型不能直接写入 `SUCCEEDED`。
- 工具仍只能经 Tool Registry、Execution Policy 和固定 workspace 执行。
- required verification 失败时 Run 不得成功。
- Approval 必须绑定 Run、actor、过期时间和不可变 action digest；输入变化后失效。
- 每次状态变化写 checkpoint；模型失败、工具调用、审批消费和验证结果写入脱敏 Trace。
- 连续重复失败动作达到阈值后终止，不能无限消耗预算。
- API key、Authorization、连接串和用户配置敏感值不得进入异常、Trace 或报告。

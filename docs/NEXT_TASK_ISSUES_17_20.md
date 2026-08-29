# 长期任务：MCP、Banking Demo、端到端任务与 Eval

**覆盖 Issues：** 17、18、19、20
**状态：** Completed（工程与 `v0.1.0` 发布证据均已完成）
**日期：** 2026-08-27

## 已完成

1. `java-project-mcp`：固定 workspace 的 STDIO JSON-RPC Server，暴露 6 个只读/验证工具并复用 Tool Runtime、Policy 和 Trace。
2. `banking-demo`：可独立构建的 Java 21/Spring fixture，包含 Account、Customer、Transfer、Transaction、Repository、Controller、Service、迁移和基线测试。
3. Dataset v0.1：enum、validation、defect-fix、query-field、transfer-limit 五类任务及确定性断言。
4. `eval-harness`：fixture 隔离、并发 Runner、Recall/编译/测试/步骤/Token/费用/时长结果、JSON/Markdown 报告。
5. task-005 E2E：从空索引开始，经 Fake/Replay Planner、matching-plan 审批、4 次受控 patch、Verification Pipeline 和真实 Maven 测试进入 `SUCCEEDED`。
6. CLI `eval-run`：单命令并发运行五任务 reference ceiling，全部实际执行 `mvn test`。
7. Quickstart、MCP 配置、Eval 说明、RC1 release notes 与发布 checklist。

## 验收结果

- 初始工程验收 `./mvnw -q spotless:apply clean verify`：通过；87 tests，0 failures，0 errors，1 skipped。
- 最终发布验收：127 tests，0 failures，0 errors，0 skipped；真实 Docker 集成测试 2/2 通过。
- JaCoCo 行覆盖率：`java-project-mcp` 82.6%，`eval-harness` 86.7%，`tool-filesystem` 83.6%。
- MCP 可执行 JAR：`initialize` 与 `tools/list` STDIO 冒烟测试通过，返回 6 个工具。
- Eval reference ceiling：5/5，五个隔离 fixture 均真实执行 Maven 测试通过。

## 发布边界

工程实现从提交 `2c7cc73` 开始形成完整闭环，最终发布提交为 `8e1a403`。真实模型五任务基线、全新环境 smoke、真实 Docker 测试、远端 CI/Security/Eval smoke、公开 GIF/视频和 `v0.1.0` tag 均已完成，证据见 [`RELEASE_CHECKLIST_V0.1.md`](RELEASE_CHECKLIST_V0.1.md)。

# 长期任务：MCP、Banking Demo、端到端任务与 Eval

**覆盖 Issues：** 17、18、19、20
**状态：** Engineering Complete / Release Actions Pending
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

- `./mvnw -q spotless:apply clean verify`：通过。
- Surefire：87 tests，0 failures，0 errors，1 skipped（本机 Docker daemon 不可用时跳过现有 Docker 集成测试）。
- JaCoCo 行覆盖率：`java-project-mcp` 82.6%，`eval-harness` 86.7%，`tool-filesystem` 83.6%。
- MCP 可执行 JAR：`initialize` 与 `tools/list` STDIO 冒烟测试通过，返回 6 个工具。
- Eval reference ceiling：5/5，五个隔离 fixture 均真实执行 Maven 测试通过。

## 发布边界

工程实现和本地验收已经完成，完整源码已提交为 `2c7cc73`。真实模型 5-task 基线、全新环境 smoke、Docker/远端 CI、公开 GIF/视频和 `v0.1.0` tag 仍需要 provider credentials、相应运行环境与人工视觉确认；当前不伪造这些外部发布证据。

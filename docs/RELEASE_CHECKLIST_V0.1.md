# v0.1.0 Release Checklist

## 已验证

- [x] Java 21 Maven Wrapper 构建
- [x] 全仓测试、Checkstyle、SpotBugs、JaCoCo
- [x] Workspace 路径、符号链接、硬链接和审批 digest 安全回归
- [x] Apache-2.0 LICENSE、NOTICE、贡献与安全披露文件
- [x] Banking baseline 独立构建
- [x] 五任务 reference ceiling 5/5
- [x] task-005 Replay Agent 从空索引经过审批、写入与真实 Maven 测试
- [x] MCP STDIO initialize、tools/list、tools/call smoke test
- [x] Quickstart、MCP、Eval、配置样例和已知限制
- [x] 审阅本地工作树并创建源码提交 `2c7cc73`

最新全新环境验收共执行 127 个测试：0 failures、0 errors、0 skipped；其中两个真实 Docker 集成测试均已执行并通过。

## 发布前仍需人工完成

- [x] 在具有 provider credentials 的隔离环境运行真实模型五任务基线，并提交模型、价格表和环境信息（GPT-5.4：首次 1/5，单次公开断言反馈重试后 5/5）
- [x] 在全新环境按 Quickstart 完成 30 分钟 smoke task（16 分 12 秒；[证据](QUICKSTART_SMOKE_2026-08-29.md)）
- [x] 在可用 Docker daemon 上通过 Docker 集成测试（2/2；[证据](QUICKSTART_SMOKE_2026-08-29.md)）
- [x] 确认远端工作流通过（`5dedd285`：[CI](https://github.com/tanglijiong/LegacyPilot/actions/runs/33235703354)、[Security](https://github.com/tanglijiong/LegacyPilot/actions/runs/33235703378)、[Eval smoke](https://github.com/tanglijiong/LegacyPilot/actions/runs/33235703386)）
- [x] 录制不使用 reference overlay 的公开演示（[GIF](assets/demo/legacy-pilot-task-005-real-model.gif) / [MP4](assets/demo/legacy-pilot-task-005-real-model.mp4) / [证据说明](PUBLIC_DEMO.md)）
- [x] 在发布 commit 上创建并推送 `v0.1.0` tag

v0.1.0 的源码、真实模型基线、全新环境 smoke、真实 Docker 测试、公开演示和远端工作流证据均已完成。

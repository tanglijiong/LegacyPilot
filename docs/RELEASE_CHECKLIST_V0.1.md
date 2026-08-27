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

本次验收共执行 87 个测试：0 failures、0 errors、1 skipped。跳过项是本机 Docker daemon 不可用时的既有 Docker 集成测试；Docker 不影响本地 Maven fixture 验证。

## 发布前仍需人工完成

- [ ] 在具有 provider credentials 的隔离环境运行真实模型五任务基线，并提交模型、价格表和环境信息
- [ ] 在全新环境按 Quickstart 完成 30 分钟 smoke task
- [ ] 在可用 Docker daemon 上通过 Docker 集成测试
- [ ] 确认远端 CI、Security 和 Eval smoke 工作流通过
- [ ] 录制不使用 reference overlay 的公开演示 GIF/视频
- [ ] 在发布 commit 上创建并推送 `v0.1.0` tag

完整 v0.1 源码已形成可审阅提交；发布 tag 仍应等待真实模型、全新环境、Docker、远端 CI 和演示证据完成。

# Quickstart 30 分钟 Smoke 记录（2026-08-29）

## 结论

在全新检出、空 Maven 依赖仓库和空 LegacyPilot 数据目录中，从开始克隆到完成 Quickstart 的构建、五任务 Eval、MCP STDIO 调用、状态恢复和授权签发共耗时 **16 分 12 秒**，低于 30 分钟目标。

远端 `main`（`7617a02f7e7e99351897e2e899ce18d9a203ec2b`）首轮在两个真实 Docker 集成测试处失败。将当前工作树中的 Docker 修复应用到该全新检出后，候选版本完成全部 smoke 项。Docker 修复 diff 的 SHA-256 为 `598b8567a262fb168fe2eb9055e927b3ca178d12cc97f57e5a1b1b7d15c0d94c`；因此远端分支在合入并推送该修复前不能视为通过。

## 环境

- 时间：2026-08-29 11:22:41–11:38:53（UTC+06:00）
- 主机：macOS，Apple Silicon；Docker Linux/arm64 daemon
- Java：OpenJDK 21.0.12.1
- Maven Wrapper：Apache Maven 3.9.16
- Git：2.47.0
- Docker：client 26.1.4，server 28.4.0
- 隔离方式：新的临时 clone；空 Maven artifact repository；空 data、work、agent-state 目录；未复用当前工作树的构建产物

## 结果

| Quickstart 项 | 结果 | 证据 |
| --- | --- | --- |
| `clean verify`（远端 `main`） | 失败 | 2 个 Docker 集成测试失败；总耗时 2 分 51 秒 |
| `clean verify`（应用当前 Docker 修复） | 通过 | 24/24 模块成功；127 tests、0 failures、0 errors、0 skipped；总耗时 4 分 31 秒 |
| 真实 Docker 集成测试 | 通过 | 2/2，通过时间 135.2 秒 |
| 五任务 reference Eval | 通过 | `reference-ceiling`，5/5；Java 21.0.12.1；零模型 token/费用 |
| MCP STDIO `initialize` | 通过 | protocol `2025-06-18`，server `legacy-pilot-java-project` 0.1.0 |
| MCP STDIO `tools/list` | 通过 | 返回 6 个工具 |
| MCP STDIO `tools/call` | 通过 | `project.read_file` 成功读取 Banking fixture 的 `pom.xml` |
| `agent-recover` | 通过 | 全新空状态返回 `[]` |
| 默认策略与 capability 签发 | 通过 | 一次性授权创建成功；token 在执行输出中已脱敏且未写入仓库 |

## 观察

- 首轮失败与当前尚未推送的 Docker bind mount/Maven 预热修复完全对应；合入该修复是远端 Quickstart 通过的前置条件。
- 冷启动主要耗时来自 Maven Central 下载和 Docker 内 Maven 依赖预热；总时间仍有约 13 分 48 秒余量。
- Lucene Vector API、SLF4J provider 和 Mockito 动态 agent 产生非阻断警告，不影响本次 smoke 结果。

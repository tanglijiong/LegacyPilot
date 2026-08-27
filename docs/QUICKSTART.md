# Quickstart

## Prerequisites

- JDK 21+
- Git 2.x
- Docker（只有 Maven sandbox 工具需要；代码检索、索引和 reference eval 可在无 daemon 时运行）

## 1. 验证工程

```bash
./mvnw clean verify
```

该命令会运行单元/集成测试、Banking fixture、端到端 Replay Agent、Checkstyle、SpotBugs 和 JaCoCo。

## 2. 运行五任务 Eval

```bash
./mvnw -q -pl apps/cli -am package
java -jar apps/cli/target/legacy-pilot-cli-0.1.0-SNAPSHOT.jar eval-run
```

输出为结构化 JSON。`reference-ceiling` 用于证明五个任务、参考实现和确定性断言可以全部通过，不代表真实模型成绩。

## 3. 启动 MCP Server

```bash
./mvnw -q -pl modules/java-project-mcp -am package
java -jar modules/java-project-mcp/target/legacy-pilot-java-project-mcp-0.1.0-SNAPSHOT.jar \
  "$(pwd)/samples/banking-demo"
```

然后由 MCP 客户端通过 STDIO 发送 `initialize`、`tools/list` 和 `tools/call`。配置示例见 [`config/mcp-server.example.json`](../config/mcp-server.example.json)。

## 4. 模型提供商

核心 Runtime 不绑定厂商。宿主应用需要提供 Spring AI `ChatModel` Bean，并通过宿主支持的安全配置机制提供凭据。仓库不接受写入源码、YAML、CLI 参数或 Trace 的 API key。

没有 `ChatModel` 时，生命周期 API、MCP、Fake/Replay、fixture 和 reference eval 仍可运行；真实模型调用会返回 `PROVIDER_UNAVAILABLE`。

## 故障排查

| 症状 | 检查 |
| --- | --- |
| “requires Java 21” | 设置 `JAVA_HOME` 指向 JDK 21，再运行 Wrapper |
| Maven MCP 工具失败 | 确认 Docker daemon 已运行，并已缓存 Maven sandbox image/dependencies |
| 项目注册被拒绝 | 清理目标仓库 dirty 状态；当前版本拒绝 submodule、Git LFS 和含凭据 URL |
| MCP 路径被拒绝 | 只能使用启动参数绑定 workspace 内的相对路径，不能使用绝对路径或 `..` |
| Agent 等待审批 | 使用 REST approval endpoint 或 CLI `agent-approve`，随后 `agent-resume` |
| 真实模型不可用 | 配置一个 Spring AI `ChatModel` Bean；不要把 key 写入仓库配置 |

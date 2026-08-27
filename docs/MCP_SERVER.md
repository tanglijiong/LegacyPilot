# Java Project / Maven MCP Server

LegacyPilot 提供一个本地 STDIO MCP Server，将会话绑定到启动时指定的单一 workspace。客户端无法在调用参数中更换 workspace。

## 构建与启动

```bash
./mvnw -pl modules/java-project-mcp -am package
java -jar modules/java-project-mcp/target/legacy-pilot-java-project-mcp-0.1.0-SNAPSHOT.jar \
  /absolute/path/to/fixed/workspace
```

可将 [`config/mcp-server.example.json`](../config/mcp-server.example.json) 复制到 MCP 客户端配置，并替换其中的绝对路径。

## 暴露工具

- `project.search_code`
- `project.find_references`
- `project.read_file`
- `git.diff`
- `maven.compile_project`
- `maven.run_tests`

工具调用复用内部 `ToolRegistry`、`ToolExecutor`、Execution Policy、输出限制和 Trace。v0.1 不暴露 `create_patch`、`apply_patch` 或任何外部 I/O 工具。

Maven 工具继续在离线 Docker sandbox 中运行；没有 Docker daemon 时，检索、读取和 diff 仍可使用，Maven 调用会返回结构化失败。

## 安全边界

- workspace 只能在进程启动时指定。
- 绝对路径、`..`、符号链接逃逸及硬链接逃逸由共享路径策略拒绝。
- 未在 MCP allowlist 中的工具返回 `UNREGISTERED_TOOL`。
- STDIO 不监听网络端口；若未来增加 HTTP transport，必须先增加认证、授权和来源校验。

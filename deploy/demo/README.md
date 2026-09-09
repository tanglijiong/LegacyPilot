# LegacyPilot 本地演示

## 启动

先启动 Docker daemon，并按 [`../deepseek/README.md`](../deepseek/README.md) 启动本地模型，然后从仓库根目录执行：

```bash
./deploy/demo/start.sh
```

本地演示默认允许最多 100 个执行步骤、50 次失败重试，以及同一无效操作连续
失败 50 次。可通过 `LEGACY_PILOT_MAXIMUM_STEPS`、
`LEGACY_PILOT_MAXIMUM_RETRIES` 和 `LEGACY_PILOT_MAXIMUM_IDENTICAL_FAILURES`
覆盖。结构化 JSON 响应默认还允许 10 次自动修正，可通过
`LEGACY_PILOT_MODEL_MAXIMUM_CORRECTIONS` 覆盖；正式环境仍使用保守默认值。

打开 `http://127.0.0.1:5173`。工作台会自动取得本次服务进程生成的开发者和审批人临时身份，无需复制 JWT。

如果端口已被其他本地服务占用，可以显式选择替代端口：

```bash
LEGACY_PILOT_DEMO_SERVER_PORT=18082 \
LEGACY_PILOT_DEMO_WEB_PORT=5174 \
./deploy/demo/start.sh
```

首次启动会完成以下工作：

1. 检查 JDK 21、Node.js、Git、Docker daemon、Banking Demo 和本地模型；
2. 在 `.legacy-pilot-demo/banking-demo` 创建独立、干净的 Banking Demo Git 快照；
3. 构建并启动 REST 服务与 Web 工作台；
4. 自动注册固定 revision 的 Banking Demo 项目；
5. 将数据库、Agent 状态、worktree 和日志保存在 `.legacy-pilot-demo`。

演示入口默认把检索上下文限制为 2,000 个保守估算 token，以适配 Docker Model Runner
的本地上下文窗口；可通过 `LEGACY_PILOT_CONTEXT_TOKEN_BUDGET` 显式调整。

停止后再次启动不会删除任务。临时身份每次服务启动都会更换，只存在于服务内存和当前浏览器标签页。

## 接入自己的项目

点击侧栏“项目”标题旁的接入按钮。可以直接点击“选择本地目录”上传浏览器所在电脑的项目，也可以填写后端主机可访问的普通目录或 Git 仓库绝对路径，以及无凭据的公共 HTTP(S) Git URL。普通目录不需要包含 `.git`；LegacyPilot 会复制文件并创建受管的内部只读快照，不修改原目录。预检不运行编译，也不要求项目包含 Java。它会在确认接入前展示：

- 完整 commit、检测到的技术栈和仓库状态；
- 固定 revision 源码查看、通用检索、Java 语义分析和 Maven 验证等实际可用能力；
- 未提交内容不会纳入分析、缺少 Java/Maven 或暂不支持 Gradle 验证等限制。

确认后工作台自动切换到新项目。同一内容快照被重复接入时会返回已有项目，不会创建重复项目。非 Git、非 Java 或不能编译的项目仍可接入；只是 Git 历史选择、Java 语义分析或 Maven 自动验证可能不可用。浏览器目录上传默认限制为单文件 16 MiB、单次 256 MiB，目录快照最多 20,000 个文件。

## 第一条演示任务

选择 `EXPLAIN`，输入：

```text
请讲清楚转账业务是如何实现的：从 HTTP 入口开始，说明主要校验、账户状态变化、数据持久化和异常路径，并为每项结论提供源码证据。
```

这条任务是只读的。它会生成与 `CHANGE` 不同的调查计划，最终返回业务讲解、未知项和绑定到固定 revision 的源码证据。

## 安全边界

- Demo profile 强制要求服务只监听 `127.0.0.1`；
- 临时身份只接受来自 loopback 的请求；
- 生产默认仍为 deny-all，银行部署仍使用 OIDC；
- 演示源码是独立快照；接入其他 dirty repository 时只固定并分析已提交 revision，未提交内容不会复制到受管 workspace；
- 设置 `LEGACY_PILOT_DEMO_SKIP_MODEL_CHECK=true` 只能用于查看界面，此时真实 Agent 任务会因没有可用模型而失败。

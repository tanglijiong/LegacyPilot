# Governed Tooling and Model Resilience

Issues 27–32 在可恢复 Harness 之上增加规则治理、外部客户端最小权限授权、多模型可用性、可评测向量检索和 Docker 依赖供应链边界。

## Policy DSL

默认策略可用 [`config/policy-default.yml`](../config/policy-default.yml) 表示。通过 `LEGACY_PILOT_POLICY_FILE` 指向自定义 YAML/JSON 文件。文档必须包含 `schemaVersion`、`revision` 和唯一 rule ID；可按 tool pattern、risk、idempotency、相对 path prefix 和 command execution mode 匹配。

决策优先级固定为 `DENY`、`REQUIRE_APPROVAL`、`ALLOW`，再比较 specificity、显式 priority 和 rule ID。无匹配规则时拒绝。未知 schema、损坏文件或冲突配置不会替换上一份已验证策略；冷启动时仍保留 secure default。Tool Trace 包含 `policyRuleId` 与 `policyRevision`。

## Capability Grant

Capability 绑定以下范围：

- subject、MCP session 和 Agent run；
- 内部 tool 名与固定 workspace；
- action digest，以及可选 plan digest；
- 过期时间和最大使用次数。

签发时只返回一次高熵 opaque token，状态文件仅保存 SHA-256 digest。消费、撤销和过期清理由持久 store 原子执行；一次性 token 并发使用只会产生一个成功调用。Trace 和报告不记录明文 token。

REST 入口：

```text
POST   /api/v1/capabilities
GET    /api/v1/capabilities
GET    /api/v1/capabilities/{id}
DELETE /api/v1/capabilities/{id}
```

CLI 提供 `capability-issue` 和 `capability-revoke`。签发命令需要明确 subject、session、run、tool、workspace 和 action digest。

## MCP 写调用

STDIO Server 新增 `project.apply_patch`。写调用使用 envelope：

```json
{
  "authorization": {
    "token": "one-time opaque token",
    "subject": "reviewer",
    "runId": "run-123",
    "planDigest": "optional sha256"
  },
  "input": {
    "path": "src/main/java/example/App.java",
    "expectedSha256": "current-content-sha256",
    "replacement": "complete replacement"
  }
}
```

Server 固定 session 为 `mcp-stdio`，workspace 仍由启动参数固定。调用必须依次通过 capability、Policy、Action Journal 和 run lease；无授权、错 scope、过期、撤销或已消费 token 在工具调用前返回结构化错误。崩溃后无法证明效果的写操作进入 `NEEDS_REVIEW`。

## 多模型路由

`RoutingModelGateway` 使用有稳定优先级的 model profiles。Profile 绑定 provider、model、阶段和优先级；所有候选共享最大尝试次数、token 和费用预算。

只允许以下 retryable 错误进入 fallback：

- `RATE_LIMIT`
- `TIMEOUT`
- `PROVIDER_UNAVAILABLE`

认证、结构化输出、内部永久错误和预算耗尽不会通过换模型绕过。Provider circuit breaker 使用可测试 Clock，在失败阈值后进入冷却期。每次成功、失败、熔断跳过和预算耗尽都产生 `ModelRouteEvent`。

## Vector Store 与 Reranker

Context Engine 提供 Embedding Provider、Vector Store 和 Reranker 端口。本地 `FileVectorStore` 使用版本化状态文件，条目绑定：

- project revision；
- embedding model 和维度；
- source file digest；
- symbol ID、path 和 source range。

同一 revision/model 重新同步时整体替换旧条目，搜索不会跨 revision 或 model。`PersistentVectorRetriever` 在 provider/store 故障时返回带原因的 degraded 结果；Exact、BM25 和 Graph 不受影响。固定测试同时计算 Recall@K 和 Reciprocal Rank，默认配置只能由基准门槛决定。

仓库提供 deterministic hash embedding 用于无外部服务的测试与 Replay，它不是生产语义模型。

## Docker 依赖治理

Maven 容器流程分为：

1. `DEPENDENCY_PREWARM`：由显式受信调用启用网络，缓存以读写方式挂载并执行 `dependency:go-offline`；
2. `EXECUTION`：固定 `--network none`，同一缓存只读挂载，运行 compile/test/static analysis。

`DependencyCacheManager` 根据 `pom.xml`、Maven Wrapper 配置等输入生成 SHA-256 内容地址，拒绝 root 外目录、符号链接和超限缓存。`DockerImagePolicy.digestPinned` 可强制镜像使用 `@sha256:` 引用。容器仍使用只读 rootfs、非 root 用户、drop all capabilities、no-new-privileges、PID/CPU/内存/临时盘限制和显式挂载。

真实 Docker 预热后离线 Banking 测试会在 daemon 和镜像可用时执行；没有 daemon 时测试明确 skip。

## 安全边界

- Capability 不能替代 Policy；两者都必须允许。
- MCP 客户端在本机运行也不会自动获得写权限。
- Model fallback 不能绕过审批、预算或输出 schema。
- Vector 故障不得清空 lexical/graph evidence。
- 网络只属于独立预热阶段，Agent 执行阶段保持离线。

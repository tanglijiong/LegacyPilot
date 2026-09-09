# 银行内网 PoC API 契约与操作示例

本文档面向在机构内网进行安装、联调和验收的实施人员。服务默认拒绝所有请求；示例中的 JWT 必须由机构 OIDC 测试租户签发，不能把示例变量或令牌写入仓库、命令历史或验收包。

## 1. 契约入口

生产配置默认关闭 OpenAPI。仅在隔离的管理员测试网中设置 `LEGACY_PILOT_OPENAPI_ENABLED=true` 后，具有 `ADMIN` 角色的身份才能读取：

- JSON 契约：`GET /v3/api-docs`
- Swagger UI：`GET /docs`

自动化测试会验证统一执行的 execute、events、source、approval、resume 和 report 路径存在于生成契约中，且非管理员无法读取契约。

## 2. 统一执行流程

以下变量只在当前受控终端会话中设置：

```bash
LP_BASE_URL=https://legacypilot.poc.bank.example
LP_DEVELOPER_TOKEN='<OIDC developer JWT>'
LP_APPROVER_TOKEN='<OIDC independent approver JWT>'
```

工作台首先读取项目和每个项目下的任务。任务摘要包含最近一次运行，可用于任务列表和状态轮询：

```bash
curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer ${LP_DEVELOPER_TOKEN}" \
  "${LP_BASE_URL}/api/v1/projects"

curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer ${LP_DEVELOPER_TOKEN}" \
  "${LP_BASE_URL}/api/v1/projects/${projectId}/tasks"
```

注册一个已固定 revision 的本地镜像仓库：

```bash
curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer ${LP_DEVELOPER_TOKEN}" \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: 4f343447-c48e-4f41-b24e-3a35d573dd83' \
  --data '{"source":"/srv/legacypilot/imports/sample-app","revision":"0123456789abcdef0123456789abcdef01234567"}' \
  "${LP_BASE_URL}/api/v1/projects"
```

使用返回的 `projectId` 创建任务，再使用返回的 `taskId` 创建 run：

```bash
curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer ${LP_DEVELOPER_TOKEN}" \
  -H 'Content-Type: application/json' \
  --data '{"requirement":"修复公开 fixture 中的边界条件错误","criteria":["Maven 测试通过","不得修改受保护文件"]}' \
  "${LP_BASE_URL}/api/v1/projects/${projectId}/tasks"

curl --fail-with-body --silent --show-error \
  -X POST \
  -H "Authorization: Bearer ${LP_DEVELOPER_TOKEN}" \
  "${LP_BASE_URL}/api/v1/tasks/${taskId}/runs"
```

异步受理服务端统一执行。客户端不提交 `ProjectIndex`、actor 或内部 Agent request；每次逻辑执行必须携带稳定且可重放的 `Idempotency-Key`：

```bash
curl --fail-with-body --silent --show-error \
  -X POST \
  -H "Authorization: Bearer ${LP_DEVELOPER_TOKEN}" \
  -H "Idempotency-Key: execute-${runId}" \
  -H "If-Match: ${runVersion}" \
  "${LP_BASE_URL}/api/v1/runs/${runId}/execute"
```

接口返回 `202 Accepted`，`Location` 指向持久化执行命令。例如：

```json
{
  "id": "command-…",
  "runId": "run-…",
  "operation": "EXECUTE",
  "status": "QUEUED",
  "attempts": 0,
  "correlationId": "4f343447-c48e-4f41-b24e-3a35d573dd83"
}
```

HTTP 请求结束后任务仍在后台运行。客户端可以轮询 run，也可以读取命令受理状态：

```bash
curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer ${LP_DEVELOPER_TOKEN}" \
  "${LP_BASE_URL}/api/v1/runs/execution-commands/${commandId}"
```

相同 run、操作和 run version 只会受理一个命令；相同幂等键重试会返回最初的命令，不会重复执行。

Web 与支持 SSE 的客户端应优先订阅运行事件。接口返回 `text/event-stream`，每条 `run-event` 包含 `sequence`、`time`、`phase`、`status`、用户可读 `summary`、`correlationId` 和底层事件 `type`：

```bash
curl --no-buffer --fail-with-body --silent --show-error \
  -H "Authorization: Bearer ${LP_DEVELOPER_TOKEN}" \
  -H 'Accept: text/event-stream' \
  "${LP_BASE_URL}/api/v1/runs/${runId}/events"
```

连接中断后，客户端将最后成功处理的 SSE `id` 作为 `Last-Event-ID` 请求头重连；服务端会从持久化 trace 补发后续事件，不需要客户端自行合并日志正文：

```bash
curl --no-buffer --fail-with-body --silent --show-error \
  -H "Authorization: Bearer ${LP_DEVELOPER_TOKEN}" \
  -H 'Accept: text/event-stream' \
  -H 'Last-Event-ID: 7' \
  "${LP_BASE_URL}/api/v1/runs/${runId}/events"
```

事件摘要使用服务端受控模板，底层 trace 在落盘前已经过敏感信息脱敏。代理不支持长连接时，客户端必须降级读取 `GET /api/v1/runs/{runId}`；轮询不得改变 run 状态。

源码证据必须通过注册项目的固定 revision 读取。客户端只能提交仓库相对路径，项目根由服务端注册记录确定，不能通过请求替换。默认返回证据行前后 5 行上下文：

```bash
curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer ${LP_DEVELOPER_TOKEN}" \
  --get \
  --data-urlencode "revision=${projectRevision}" \
  --data-urlencode 'path=src/main/java/example/TransferService.java' \
  --data-urlencode 'startLine=29' \
  --data-urlencode 'endLine=34' \
  --data-urlencode 'contextLines=5' \
  "${LP_BASE_URL}/api/v1/projects/${projectId}/source"
```

响应包含 `contentSha256`（原始 Git blob）、`requestedRangeSha256`（精确请求行）和 `snippetSha256`（包含上下文的展示片段）。Artifact 中的 `evidence[].excerptSha256` 应与精确证据行哈希一致；页面还会同时核对 revision 和摘录正文。`fullFile=true` 可读取受控全文，但仍受 1 MiB 文件上限、2,000 行返回上限和 UTF-8 文本限制。

绝对路径、包含 `..` 的路径、符号链接、二进制文件、错误 revision、超限读取以及无源码读取角色的身份都会被拒绝。源码接口只允许 `ADMIN`、`DEVELOPER` 和 `AUDITOR`；`APPROVER` 不因拥有审批权限而获得源码访问权限。每次允许或拒绝都会记录元数据审计事件，审计日志不包含源码正文或摘录。

CHANGE 任务进入审批前，可先读取服务端决策包。该响应统一包含计划及版本、预计影响文件、风险、策略检查、相对 Git `HEAD` 的文件/行级差异、验证分类、审批记录，以及服务端生成的 action/plan digest：

```bash
curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer ${LP_APPROVER_TOKEN}" \
  "${LP_BASE_URL}/api/v1/runs/${runId}/change-review"
```

差异最多展示 800 行，单文件最多读取 1 MiB；超限或不安全路径会在对应文件上标为不可审批的截断/错误状态。客户端只负责展示该决策包，不得从 action input 自行推导可信摘要。

读取 checkpoint 和审批上下文。审批上下文由服务端按照运行时相同的规范计算 `actionDigest` 与 `planDigest`，客户端不得自行计算。提交审批时服务端会与当前 checkpoint 再次精确核对双 digest，旧计划或旧动作的审批请求返回 `409 EXECUTION_CONFLICT`。审批必须使用与发起人不同的 OIDC 身份：

```bash
curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer ${LP_APPROVER_TOKEN}" \
  "${LP_BASE_URL}/api/v1/runs/${runId}/execution"

curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer ${LP_APPROVER_TOKEN}" \
  "${LP_BASE_URL}/api/v1/runs/${runId}/approval-context"

curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer ${LP_APPROVER_TOKEN}" \
  -H 'Content-Type: application/json' \
  --data "{\"expectedVersion\":${runVersion},\"actionDigest\":\"${actionDigest}\",\"planDigest\":\"${planDigest}\",\"decision\":\"APPROVED\",\"scope\":\"ONCE\",\"reason\":\"独立复核通过\"}" \
  "${LP_BASE_URL}/api/v1/runs/${runId}/approvals"
```

审批后恢复执行并读取最终报告：

```bash
curl --fail-with-body --silent --show-error \
  -X POST \
  -H "Authorization: Bearer ${LP_DEVELOPER_TOKEN}" \
  -H "Idempotency-Key: resume-${runId}-${actionDigest}" \
  -H "If-Match: ${decisionRunVersion}" \
  "${LP_BASE_URL}/api/v1/runs/${runId}/resume"

curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer ${LP_DEVELOPER_TOKEN}" \
  "${LP_BASE_URL}/api/v1/runs/${runId}/report"
```

恢复接口同样返回 `202 Accepted`。如果状态再次为 `WAITING_FOR_APPROVAL`，应重新读取 checkpoint、独立审批对应 action，并以新 action digest 构造新的 resume 幂等键。服务不会因轮询自动批准或执行写操作。

拒绝使用同一审批接口，将 `decision` 改为 `DENIED` 并提供非空 `reason`，随后调用 resume 消费该决定。运行时会以 `DENIED` 终止待执行动作，审计 trace 保留审批人、理由和双 digest；继续修改必须创建新的 CHANGE 任务重新规划，旧审批不会复用。

运行恢复决策由服务端生成，页面不得仅凭错误文字猜测可执行操作：

```bash
curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer ${LP_DEVELOPER_TOKEN}" \
  "${LP_BASE_URL}/api/v1/runs/${runId}/recovery"
```

响应区分 `USER_CANCELLED`、`MODEL_FAILURE`、`TOOL_FAILURE`、`POLICY_BLOCK`、`VERIFICATION_FAILURE` 与 `INFRASTRUCTURE_FAILURE`，并返回 `replaySafety`、`allowedActions`、当前 `expectedVersion` 和持久化 attempt 列表。`HUMAN_REVIEW_REQUIRED` 不会提供自动重试。

取消、重试和重新规划必须提交刚读取到的版本；另一个页面先改变状态后，旧请求返回 `409 CONCURRENT_UPDATE`：

```bash
curl --fail-with-body --silent --show-error -X POST \
  -H "Authorization: Bearer ${LP_DEVELOPER_TOKEN}" \
  -H "If-Match: ${runVersion}" \
  -H 'Content-Type: application/json' \
  --data '{"reason":"用户取消"}' \
  "${LP_BASE_URL}/api/v1/runs/${runId}/cancel"

curl --fail-with-body --silent --show-error -X POST \
  -H "Authorization: Bearer ${LP_DEVELOPER_TOKEN}" \
  -H "If-Match: ${runVersion}" \
  -H "Idempotency-Key: retry-${runId}-${runVersion}" \
  "${LP_BASE_URL}/api/v1/runs/${runId}/retry"
```

重新规划使用同样的请求头调用 `/api/v1/runs/{runId}/replan`。服务重启后，未完成的 RETRY/REPLAN command 会由 lease 扫描器重新领取；旧 trace、action journal 和 command attempt 不会被覆盖。Maven 门禁失败归为验证失败，模型超时归为模型失败，后台 worker/lease 异常归为基础设施失败。

## 3. 错误契约

API 使用 `application/problem+json`。每个由应用或安全层产生的错误至少包含：

```json
{
  "type": "about:blank",
  "title": "Forbidden",
  "status": 403,
  "detail": "The authenticated identity is not authorized for this operation",
  "code": "ACCESS_DENIED",
  "correlationId": "4f343447-c48e-4f41-b24e-3a35d573dd83"
}
```

响应头同时返回 `X-Correlation-ID`。客户端提供合法 UUID 时服务会透传；缺失或非法时服务会生成新的 UUID。实施方应使用 HTTP 状态和稳定的 `code` 做机器判断，将 correlation ID 用于日志和审计关联，不应解析 `detail` 文本。

当前稳定错误码包括：

| code | HTTP | 含义 |
| --- | ---: | --- |
| `AUTHENTICATION_REQUIRED` | 401 | 缺少或无法验证身份 |
| `ACCESS_DENIED` | 403 | 身份没有对应角色或权限 |
| `RESOURCE_NOT_FOUND` | 404 | 项目、任务、run、checkpoint 或报告不存在 |
| `INVALID_REQUEST` | 400 | 请求字段、标识或参数不合法 |
| `CONCURRENT_UPDATE` | 409 | 乐观锁或并发更新冲突 |
| `INVALID_STATE_TRANSITION` | 409 | 当前 run 状态不允许该操作 |
| `WORKSPACE_CONFLICT` | 409 | worktree、路径或工作区操作冲突 |
| `EXECUTION_CONFLICT` | 409 | 其他可安全重试或需人工处理的执行冲突 |

## 4. 兼容管理接口

`/api/v1/agent-runs` 是管理员兼容与恢复接口，允许提交内部 `AgentRunRequest`，不应提供给普通开发者。新的银行侧集成应使用 `/api/v1/projects`、`/tasks` 和 `/runs/{runId}/execute` 统一流程。

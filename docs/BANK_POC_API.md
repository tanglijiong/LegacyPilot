# 银行内网 PoC API 契约与操作示例

本文档面向在机构内网进行安装、联调和验收的实施人员。服务默认拒绝所有请求；示例中的 JWT 必须由机构 OIDC 测试租户签发，不能把示例变量或令牌写入仓库、命令历史或验收包。

## 1. 契约入口

生产配置默认关闭 OpenAPI。仅在隔离的管理员测试网中设置 `LEGACY_PILOT_OPENAPI_ENABLED=true` 后，具有 `ADMIN` 角色的身份才能读取：

- JSON 契约：`GET /v3/api-docs`
- Swagger UI：`GET /docs`

自动化测试会验证统一执行的 execute、approval、resume 和 report 路径存在于生成契约中，且非管理员无法读取契约。

## 2. 统一执行流程

以下变量只在当前受控终端会话中设置：

```bash
LP_BASE_URL=https://legacypilot.poc.bank.example
LP_DEVELOPER_TOKEN='<OIDC developer JWT>'
LP_APPROVER_TOKEN='<OIDC independent approver JWT>'
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

启动服务端统一执行。客户端不提交 `ProjectIndex`、actor 或内部 Agent request：

```bash
curl --fail-with-body --silent --show-error \
  -X POST \
  -H "Authorization: Bearer ${LP_DEVELOPER_TOKEN}" \
  "${LP_BASE_URL}/api/v1/runs/${runId}/execute"
```

读取 checkpoint，从待审批 action 中取得 `actionDigest` 和 `planDigest`。审批必须使用与发起人不同的 OIDC 身份：

```bash
curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer ${LP_APPROVER_TOKEN}" \
  "${LP_BASE_URL}/api/v1/runs/${runId}/execution"

curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer ${LP_APPROVER_TOKEN}" \
  -H 'Content-Type: application/json' \
  --data "{\"actionDigest\":\"${actionDigest}\",\"planDigest\":\"${planDigest}\",\"decision\":\"APPROVED\",\"scope\":\"ONCE\",\"reason\":\"独立复核通过\"}" \
  "${LP_BASE_URL}/api/v1/runs/${runId}/approvals"
```

审批后恢复执行并读取最终报告：

```bash
curl --fail-with-body --silent --show-error \
  -X POST \
  -H "Authorization: Bearer ${LP_DEVELOPER_TOKEN}" \
  "${LP_BASE_URL}/api/v1/runs/${runId}/resume"

curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer ${LP_DEVELOPER_TOKEN}" \
  "${LP_BASE_URL}/api/v1/runs/${runId}/report"
```

如果状态再次为 `WAITING_FOR_APPROVAL`，应重新读取 checkpoint、独立审批对应 action，再显式 resume。服务不会因轮询自动批准或执行写操作。

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

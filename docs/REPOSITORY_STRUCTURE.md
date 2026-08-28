# LegacyPilot 仓库结构

## 1. 组织方式

采用 Maven 多模块 Monorepo。模块按能力边界划分，但 v0.1 作为一个 Spring Boot 部署单元交付。这样既能展示清晰架构，也能避免过早引入分布式系统复杂度。

## 2. 目标目录

```text
LegacyPilot/
├── README.md
├── LICENSE
├── NOTICE
├── CONTRIBUTING.md
├── SECURITY.md
├── CODE_OF_CONDUCT.md
├── pom.xml
├── .editorconfig
├── .gitignore
├── .github/
│   ├── workflows/
│   │   ├── ci.yml
│   │   ├── security.yml
│   │   └── eval-smoke.yml
│   ├── ISSUE_TEMPLATE/
│   └── pull_request_template.md
├── docs/
│   ├── README.md
│   ├── PRD.md
│   ├── ARCHITECTURE.md
│   ├── REPOSITORY_STRUCTURE.md
│   ├── ROADMAP.md
│   ├── MVP_BACKLOG.md
│   ├── DECISIONS.md
│   └── adr/
├── apps/
│   ├── server/
│   └── cli/
├── modules/
│   ├── domain/
│   ├── application/
│   ├── bootstrap/
│   ├── agent-runtime/
│   ├── model-spi/
│   ├── model-spring-ai/
│   ├── java-analyzer/
│   ├── context-engine/
│   ├── tool-spi/
│   ├── tool-filesystem/
│   ├── tool-git/
│   ├── tool-maven/
│   ├── workspace/
│   ├── sandbox-docker/
│   ├── verification/
│   ├── observability/
│   ├── durable-state/
│   ├── persistence/
│   ├── reporting/
│   └── evaluation/
├── mcp-servers/
│   └── java-project-mcp/
├── ui/
│   └── dashboard/                 # v0.3
├── samples/
│   ├── banking-demo/
│   └── task-configs/
├── evals/
│   ├── datasets/
│   │   └── v0.1/
│   ├── expected/
│   └── baselines/
├── deploy/
│   ├── docker/
│   ├── compose.yml
│   └── otel/
└── scripts/
    ├── dev/
    ├── demo/
    └── eval/
```

目录表达的是目标状态，不要求第一个提交一次生成所有空模块。按 Backlog 逐步创建，避免只有目录没有实现。

## 3. 模块职责

| 模块 | 职责 | 禁止依赖 |
| --- | --- | --- |
| `domain` | Task、Plan、State、Approval、Verification 等纯领域模型 | Spring、模型厂商、数据库实现 |
| `application` | 生命周期 Use Cases 与向内的 Repository/Workspace ports | Controller、数据库和 Git 实现 |
| `bootstrap` | 组合 Application ports 与 Adapter 的 Spring 配置 | 业务规则复制 |
| `agent-runtime` | 状态机、Agent Loop、Budget、Planner/Context/Evaluator 协作 | 具体 Tool、具体模型 SDK |
| `model-spi` | 厂商无关的模型请求、Structured Output、Usage | Spring AI 实现细节 |
| `model-spring-ai` | 基于 Spring AI 的模型适配器 | 反向污染 domain |
| `java-analyzer` | AST、符号、Spring 角色、引用和依赖边 | LLM 调用 |
| `context-engine` | 多路召回、排序、图扩展、Token packing | 具体 API/UI |
| `tool-spi` | Tool 契约、描述符、风险、错误语义 | 具体工具实现 |
| `tool-*` | 文件、Git、Maven 的受控实现 | Agent Runtime 内部状态 |
| `workspace` | Git clone/worktree、生命周期、路径边界 | 模型调用 |
| `sandbox-docker` | 容器执行、资源与网络限制 | 业务计划逻辑 |
| `verification` | 编译、测试、静态分析、风险和终态证据 | 模型对“完成”的声明 |
| `observability` | Trace、Metric、脱敏、导出 | 业务决策 |
| `durable-state` | 版本化 JSON envelope、原子替换、迁移、备份与损坏隔离 | Agent/业务类型 |
| `persistence` | Repository 实现与迁移 | Controller 逻辑 |
| `reporting` | JSON/Markdown 报告 | 直接执行工具 |
| `evaluation` | 数据集解析、Runner、指标聚合 | 特定 Demo 的硬编码 |
| `java-project-mcp` | 将允许工具暴露为 MCP | 绕过 Tool Runtime/Policy |

## 4. 依赖方向

```text
apps/server, apps/cli, mcp-servers
                 |
                 v
agent-runtime, context-engine, verification, evaluation
                 |
                 v
domain, model-spi, tool-spi

Adapters:
model-spring-ai, tool-*, persistence, sandbox-docker, observability
implement inward-facing ports and are wired only at application composition.
```

构建中应使用架构测试约束依赖方向，禁止 `domain` 依赖 Spring 或 Adapter。

## 5. 包命名建议

根包：`io.legacypilot`

```text
io.legacypilot.domain.task
io.legacypilot.runtime
io.legacypilot.context
io.legacypilot.analysis.java
io.legacypilot.tool
io.legacypilot.workspace
io.legacypilot.verification
io.legacypilot.evaluation
```

模块内部按领域能力组织，避免顶层统一使用 `controller/service/repository/util` 造成边界模糊。

## 6. 配置分层

```text
application.yml                 # 安全默认值
application-local.yml           # 本地开发，不提交密钥
legacy-pilot.project.yml        # 目标项目适配配置
.env.example                    # 仅变量名与示例占位符
```

目标项目配置建议包含：

```yaml
project:
  build:
    tool: maven
    compileGoals: ["test-compile"]
    testGoals: ["test"]
  verification:
    required: ["compile", "test"]
    optional: ["spotbugs", "checkstyle", "jacoco"]
  workspace:
    writableGlobs: ["src/**", "pom.xml"]
    protectedGlobs: ["**/.env", "**/*secret*", ".git/**"]
```

配置解析必须使用结构化字段，不能把配置值直接拼接成 shell 命令。

## 7. Eval 数据布局

```text
evals/datasets/v0.1/task-001/
├── task.yml
├── fixture.ref
├── assertions.yml
└── README.md
```

`task.yml` 定义需求、预算和允许工具；`fixture.ref` 固定仓库/提交；`assertions.yml` 定义确定性验收；运行结果进入 `evals/baselines/<date-model-config>/`，但大型原始 Trace 不提交 Git。

## 8. 文档与代码同步

- 公共接口、工具 Schema 和配置格式使用代码生成或测试校验文档示例。
- 每项架构变更放入 `docs/adr/NNNN-title.md`。
- 每个版本 tag 对应一份公开 Eval 基线和运行环境说明。

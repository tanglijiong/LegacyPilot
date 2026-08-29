# 下一阶段长期任务：正式收口 v0.2 与证据优先的精简 v0.3

**覆盖建议 Issues：** 33–40
**阶段目标：** 先发布可审计的 `v0.2.0`，再用 20 个核心任务（30 个为 stretch）证明策略、模型和检索改动是否真的提升质量
**状态：** Planned
**规划日期：** 2026-08-29

## 1. 为什么这样收敛

LegacyPilot 的核心价值是可治理、可恢复、可评测的 Java Agent Harness，而不是不断扩大功能边界。Issues 21–32 已完成 v0.2 的本地工程能力；下一阶段先把这些能力变成正式发布证据，再只实现 v0.3 中最能增强可信度的四件事：

1. 扩展到 20 个核心、最多 30 个真实且多样化的任务；
2. 对模型、Prompt 和策略做成对回归比较；
3. 统一记录成功率、成本、耗时和失败分类；
4. 生成一个只读、自包含的静态结果 Dashboard。

本阶段不建设通用 AgentOps 平台，不引入多 Agent、Kubernetes、实时流式监控、用户系统或完整 React 应用，也不进入 Java 升级和 COBOL 场景。

## 2. 范围与完成定义

### 2.1 v0.2 正式收口

`v0.2.0` 必须具备：

- Issues 21–32 的迁移说明、威胁模型变化和运维说明；
- JDK 21 全仓验证、真实 Docker 集成测试和新环境 Quickstart；
- 恢复、Journal/Lease、Capability、MCP 授权写、模型 fallback、Vector/Reranker 和离线 Docker 缓存的公开证据；
- v0.1 五任务真实模型回归不低于既有发布门槛；
- `v0.2.0` release checklist、annotated tag 和 GitHub Release。

### 2.2 精简 v0.3 完成定义

- 冻结 20 个核心任务；任务 21–30 作为达到质量与预算门槛后的 stretch；
- 至少覆盖 3 个固定 fixture、6 类任务和 4 类变更规模；
- reference ceiling 20/20，fixture、测试和断言完整性检查 100% 通过；
- 至少完成一次同模型、不同 Prompt/策略的成对实验；
- 所有任务都生成可恢复的 attempt 证据、失败分类、Token、成本和耗时；
- 生成 Markdown、JSON 和自包含 HTML Dashboard；
- 发布真实结果，包括失败项，不用 reference ceiling 或重试后成绩替代 pass@1。

## 3. 工作包

### Issue 33 — v0.2 发布收口与文档校准

#### 工作内容

- 更新仍写着 v0.1 外部动作待完成的历史文档，并为 Issues 12–32 补齐明确状态；
- 解决 PRD/架构中的 Docker Compose 承诺与实际实现不一致：实现最小 Compose，或明确延期并说明 H2/本地进程替代路径；
- 把现有 RC1 说明升级为最终 v0.1 GitHub Release，再编写 v0.2 release notes、迁移说明和 checklist；
- 审阅 Dependabot PR；大版本升级不与 v0.2 发布混合，除非存在明确安全阻断；
- 重跑完整验证、Docker、Quickstart、远端 CI/Security/Eval smoke 和 v0.1 五任务真实模型回归。

#### 验收条件

- 文档中不存在把已完成 v0.1 动作描述为待办的现行状态段落；
- `v0.2.0` 的所有证据链接可从 README 或 release notes 到达；
- 发布提交工作区干净，三项远端工作流通过，tag 与 GitHub Release 指向同一提交。

### Issue 34 — Eval v0.2 Schema、fixture registry 与数据治理

#### 工作内容

- 将单一 fixture 参数升级为 fixture registry；每个 fixture 固定来源、revision、许可证、SHA-256 和构建命令；
- 扩展 task schema：难度、变更类型、预期影响面、允许/禁止文件、公开断言、隐藏行为测试、超时和资源预算；
- 断言从 `FILE_EXISTS/CONTAINS/NOT_CONTAINS` 扩展为编译、测试、结构、API 合约和禁止变更检查；
- reference solution 只用于 ceiling 和断言校准，provider-backed 运行从进程参数、工作区和索引层均不可访问；
- dataset manifest 记录整体哈希，任务一旦进入公开基线不得原地改写，只能升版本。

#### 验收条件

- 相同 dataset manifest 在不同机器产生相同任务顺序和 fixture digest；
- 篡改测试、`pom.xml`、reference、fixture revision 或允许范围时 fail closed；
- 每个任务可独立执行 reference ceiling 和 integrity check。

### Issue 35 — 核心任务包 A（task-006–015）

构建首批 10 个任务，用于先验证 schema 与 runner，不立刻扩到 30 个。

建议覆盖：

- 2 个领域规则与跨文件功能；
- 2 个真实缺陷修复，包含空值、边界或异常映射；
- 2 个 REST/API 合约与 validation；
- 2 个 persistence/query/schema 变更；
- 1 个时间、时区或并发问题；
- 1 个安全或敏感信息处理问题。

任务需求应来自真实 issue/PR 模式或公开项目维护场景，不为当前 Agent 的实现路径量身定制。每个任务先由人工/reference 验证唯一可判定的行为结果，再进入模型实验。

### Issue 36 — 核心任务包 B（task-016–020）与 stretch gate

再增加 5 个任务，使包含原始 task-001–005 的核心数据集达到 20 个，并补足首批数据中不足的类别。

#### 数据集最低分布

| 维度 | 最低要求 |
| --- | ---: |
| 固定 fixture | 3 个 |
| 任务类别 | 6 类 |
| 跨文件任务 | 30% |
| 时间/并发/数据边界 | 20% |
| 负向或安全行为 | 20% |
| 需要修改测试之外生产代码 | 100% |

只有在核心 20 个任务的 ceiling、完整性和运行预算稳定后，才增加 task-021–030 中的 10 个 stretch 任务。30 个不是发布硬门槛。

### Issue 37 — 可恢复的真实模型 Eval Runner

#### 运行协议

- 每次实验先写不可变 run manifest：Git SHA、dataset SHA、模型、Prompt SHA、策略/Policy 版本、价格快照和环境；
- 每个 task/attempt 使用稳定 ID，开始前写 checkpoint，完成后原子写结果；
- `--resume` 跳过已确认完成的 attempt，不重复计费；不确定状态进入 `NEEDS_REVIEW`；
- 支持总成本、单任务成本、总时长、步骤、Token、并发和 provider error budget；
- SIGINT、进程崩溃、网络中断和 provider 限流后可恢复；
- 原始凭据不进入命令参数、日志或 artifact；模型输出与工具输出继续执行集中脱敏。

#### 推荐命令形态

```text
eval run --dataset evals/datasets/v0.3 --profile baseline-a --output evals/runs/<run-id>
eval run --resume evals/runs/<run-id>
eval compare --baseline <run-a> --candidate <run-b>
eval dashboard --comparison <comparison.json> --output dashboard.html
```

#### 故障注入验收

- 在 task 开始前、模型响应后、写补丁后和结果落盘前分别中断；
- 恢复后已成功 attempt 的 provider 调用次数不增加；
- 超过成本或时间预算后不再启动新任务，但保留已完成结果；
- 单个任务 ERROR 不丢失整个实验的其他结果。

### Issue 38 — 实验矩阵、失败分类与回归门槛

#### 最小实验矩阵

1. **A：发布基线** — 当前模型 + 当前 Prompt/策略；
2. **B：策略候选** — 同一模型，仅改变 Prompt/策略，隔离策略效果；
3. **C：可选成本候选** — 只有 A/B 稳定后才引入第二模型。

先对 5 个 smoke 任务运行 A/B；通过后对 20 个核心任务做一次成对全量运行。只对 A/B 结果不一致或出现 provider/infrastructure error 的任务追加最多两次复跑，避免无边界烧预算。

#### 指标

- pass@1、一次公开反馈重试后的最终成功率；
- assertion、compile、test、retrieval recall；
- steps、retry、input/cached/output token；
- 单任务与总成本、duration p50/p95；
- 工具失败率、provider 错误率和完整性违规数；
- 按 category、fixture、难度和变更规模切片。

#### 失败分类

每个非成功任务必须有且只有一个 primary failure，并可附 secondary causes：

- `RETRIEVAL_MISS`
- `PLAN_ERROR`
- `PATCH_ERROR`
- `COMPILE_FAILURE`
- `TEST_FAILURE`
- `ASSERTION_FAILURE`
- `POLICY_OR_APPROVAL_BLOCK`
- `TOOL_OR_SANDBOX_FAILURE`
- `PROVIDER_FAILURE`
- `BUDGET_EXHAUSTED`
- `DATASET_OR_INFRA_FAILURE`

分类首先由确定性阶段与错误码生成；只有无法确定时才允许人工复核，不使用 LLM judge 决定任务成功。

#### 发布门槛

- integrity violation 必须为 0；
- v0.1 五任务不得回归到 4/5 以下；
- 核心 20 任务目标：pass@1 ≥ 70%，最多一次公开反馈后 ≥ 85%；
- 若未达到门槛，仍发布完整实验报告，但不把候选策略升级为默认值；
- 候选策略最多允许比基线少通过 1/20，且只有成本或 p95 时长改善至少 15% 时才接受这一损失。

### Issue 39 — 自包含静态 Eval Dashboard

Dashboard 是报告视图，不是新的在线产品。

#### 页面内容

- 顶部：成功率、pass@1、总成本、平均成本、p50/p95、失败数；
- A/B 对比：成功变化、成本变化、耗时变化和回归任务；
- 任务表：按 fixture/category/status/failure 过滤；
- 两个简单图表：失败分类分布、成功率与成本对比；
- 每项指标可回链到 manifest、task result 和脱敏 artifact。

#### 技术边界

- 由 Java renderer 生成单个 HTML 文件，CSS/JavaScript 内嵌；
- 不新增 Node 构建链、数据库、登录、编辑、实时 websocket 或长期运行服务；
- Dashboard 在无网络浏览器中可打开，内容由同一份 comparison JSON 生成；
- renderer 做快照测试、HTML 转义测试和 20/30 任务布局检查。

### Issue 40 — 公开基线、决策记录与下一阶段出口

- 冻结 dataset、runner 和 experiment schema 版本；
- 运行 A/B 全量实验并提交 manifest、逐任务结果、comparison、Markdown 与 Dashboard；
- 记录模型别名/快照限制、Prompt/策略 SHA、价格来源和运行环境；
- 发布失败项、人工复核项与已知限制；
- 根据证据决定：发布精简 v0.3、继续优化策略，或停止扩展并进入维护模式。

## 4. Artifact 布局

```text
evals/
  fixtures/<fixture-id>/provenance.yml
  datasets/v0.3/manifest.yml
  datasets/v0.3/task-006/...
  profiles/<profile-id>.yml
  runs/<run-id>/
    manifest.json
    tasks/<task-id>/attempt-<n>.json
    tasks/<task-id>/result.json
    summary.json
  comparisons/<comparison-id>/
    comparison.json
    report.md
    dashboard.html
```

大体积原始日志不进入 Git；仓库只提交复现所需的脱敏结果、摘要与稳定 artifact。完整原始运行目录可作为 CI artifact 设置保留期。

## 5. 长时间运行检查点

| Checkpoint | 可交付结果 | 进入下一步的条件 |
| --- | --- | --- |
| 0 | 范围冻结、Issue 33–40 | 无新增平台化范围 |
| 1 | `v0.2.0` 正式发布 | release checklist 全绿 |
| 2 | Eval v0.2 schema + fixture registry | integrity/fail-closed 测试通过 |
| 3 | task-006–015 | 10/10 ceiling，类别分布合格 |
| 4 | task-016–020 | 核心 20/20 ceiling，manifest 冻结 |
| 5 | resumable provider runner | 四类中断恢复与预算测试通过 |
| 6 | A/B smoke | 5 个任务无基础设施错误 |
| 7 | A/B 全量与 comparison | 20 个任务均有终态与失败分类 |
| 8 | 静态 Dashboard 与公开报告 | JSON/Markdown/HTML 数值一致 |
| 9 | v0.3 决策 | 达标则发布，否则保留证据并继续迭代 |

每个 checkpoint 单独提交并运行全仓验证。不得在 dataset 未冻结、ceiling 未通过或 runner 不可恢复时启动昂贵的全量模型实验。

## 6. 预算与停止条件

- 默认并发 2；只有 provider rate limit 和宿主资源证据允许时才提高；
- A/B smoke 使用独立小预算；全量实验必须设置显式 USD、Token 和 wall-clock 上限；
- 建议首轮全阶段 API 等价预算上限 `$30`，超过前必须重新评估任务长度和重试策略；
- 同一阻塞条件连续出现三轮或数据集本身不可判定时停止扩展任务，先修复基础设施；
- 不为了达到分数修改隐藏测试、降低断言或向模型暴露 reference solution。

## 7. 推荐执行顺序

```text
33 v0.2 release
  -> 34 schema / fixture governance
  -> 35 task pack A
  -> 37 resumable runner
  -> 36 task pack B + dataset freeze
  -> 38 A/B comparison + failure taxonomy
  -> 39 static dashboard
  -> 40 public evidence and v0.3 decision
```

Runner 在第二批任务之前完成，确保后续真实模型运行从一开始就具备 checkpoint、预算和恢复能力。Dashboard 最后实现，以已经冻结的 comparison schema 为输入，避免先做 UI 再反复改数据契约。

## 8. 建议时间盒

| 工作包 | 预计投入 | 主要不确定性 |
| --- | ---: | --- |
| Issue 33：v0.2 收口 | 1–2 天 | 文档校准、依赖 PR 与外部发布流程 |
| Issue 34：schema/fixture 治理 | 1–2 天 | 多 fixture 构建差异和隐藏测试契约 |
| Issue 35：首批 10 个新任务 | 2–4 天 | 任务来源、许可证与断言质量 |
| Issue 37：可恢复 runner | 2–3 天 | provider 中断语义和计费去重证据 |
| Issue 36：后续 5 个核心任务 | 1–2 天 | 类别分布与跨 fixture 稳定性 |
| Issue 38：比较与失败分类 | 1–2 天 + 模型运行时间 | 模型波动、限流和差异任务复跑 |
| Issue 39：静态 Dashboard | 1–2 天 | 20/30 任务下的可读性 |
| Issue 40：公开证据与决策 | 0.5–1 天 | 外部工作流和人工复核 |

总计约 10–18 个专注开发日，不包含等待 provider、CI 和人工审阅的墙钟时间。每完成一个 checkpoint 都可以安全停止；下次从已提交证据继续，不要求一次连续跑完。

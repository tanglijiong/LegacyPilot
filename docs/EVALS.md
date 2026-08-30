# Eval Harness 与公开基线

## 单命令运行

```bash
./mvnw -q -pl apps/cli -am package
java -jar apps/cli/target/legacy-pilot-cli-0.3.0-SNAPSHOT.jar eval-run
```

默认加载经过 manifest 和 fixture digest 验证的 `evals/datasets/v0.3` core。也可通过 `--dataset`、`--references`、`--maven-wrapper` 和 `--concurrency` 指定路径与并发数；`--fixture` 仅用于兼容没有 manifest 的 v0.1 数据集。v2 契约详见 [Eval Dataset v2 与 Fixture 治理](EVAL_DATASET_V2.md)。

## 可恢复的内网模型实验

Runner 核心只依赖通用 `EvalModelAdapter`，不依赖模型厂商。默认适配器使用两个均为 `--network none` 的本地容器：持久化 vLLM 服务只读挂载模型权重并监听 Unix-domain socket；每个任务的短生命周期 Agent 只挂载该 socket 和隔离 workspace。模型只加载一次，两个容器均不能访问内外网。

镜像必须由银行内部镜像仓库预置并固定摘要。Agent 从标准输入读取 Prompt，在 `/workspace` 修改代码并输出 JSONL；新实验必须显式提供模型、Prompt、权重目录、权重审批摘要、socket 目录和价格快照。启动器会在模型健康后写入服务 manifest，Runner 必须逐项核对镜像、模型摘要和资源配置。私有化模型没有按 Token 费用时价格可以记录为 `0`，另行统计 GPU 基础设施成本。DeepSeek 镜像构建和持久化服务启动见 [`deploy/deepseek`](../deploy/deepseek/README.md)。

```bash
./mvnw -q -pl apps/cli -am package -DskipTests
java -jar apps/cli/target/legacy-pilot-cli-0.3.0-SNAPSHOT.jar eval-model-run \
  --output evals/runs/<run-id> \
  --run-id <run-id> \
  --model-adapter airgap-container \
  --agent-image registry.bank.local/legacy-pilot/model-agent@sha256:<64-hex-digest> \
  --agent-command /opt/legacy-pilot/model-agent \
  --model-weights /srv/legacy-pilot/models/deepseek-coder-v2-lite \
  --model-socket-directory /run/legacy-pilot/deepseek \
  --model-artifact-sha256 <64-hex-weights-digest> \
  --agent-memory 24g --agent-cpus 8 --agent-pids 1024 \
  --agent-gpus all --tensor-parallel-size 1 --max-model-length 32768 \
  --model deepseek-coder-v2-lite \
  --reasoning-effort high \
  --prompt-file evals/prompts/baseline-prompt-v2.md \
  --prompt-version baseline-prompt-v2 \
  --policy-version bank-airgap-agent-v1 \
  --input-price <usd-per-1m> \
  --cached-input-price <usd-per-1m> \
  --output-price <usd-per-1m> \
  --pricing-source <source> \
  --maximum-cost-usd 10 \
  --maximum-tokens 2000000 \
  --maximum-duration PT4H \
  --maximum-provider-errors 3 \
  --concurrency 1 \
  --task-ids task-001,task-002,task-003,task-004,task-005
```

首轮固定为上述 5-task smoke，目标至少 4/5。通过后创建新的 run-id，移除 `--task-ids` 运行完整 20-task；不能把 smoke checkpoint 扩展成 full run，因为任务集合属于不可变 manifest。

恢复时只需指定相同 dataset 和实验目录；适配器、固定镜像、网络边界和 Agent 入口来自不可变 manifest，不能在恢复时切换：

```bash
java -jar apps/cli/target/legacy-pilot-cli-0.3.0-SNAPSHOT.jar eval-model-run \
  --output evals/runs/<run-id> --resume
```

Runner 在调用前原子写入稳定 attempt checkpoint。已完成任务不会再次调用模型；进程退出时仍为 `RUNNING` 的 attempt 会转为 `NEEDS_REVIEW`，其余待执行任务继续。全局成本、Token、累计耗时、provider error 和并发数均来自不可变 manifest。Prompt 只通过标准输入传递；父进程会移除 credential、token、password、authorization 和 proxy 环境变量。模型完成后才会叠加隐藏测试，reference production code 永远不会进入模型工作区。

### 显式外部公开基准

Codex 兼容适配器只用于公开合成 fixture 的研发基准，不是银行部署路径。它必须同时提供 `--model-adapter codex` 和 `--allow-external-provider`；缺少任一项都会拒绝启动，恢复外部实验也必须再次显式确认。任何真实银行代码、日志、Prompt 或凭据都不得使用该模式。

### 密钥与零外网门禁

- Gitleaks v3 在 Security 工作流中扫描完整 Git 历史，不只检查当前文件；白名单只包含精确的测试哨兵值。
- Air-gap 命令构造测试固定验证 `--pull never`、`--network none`、只读根文件系统和权限收缩参数。
- `.env`、本地配置、运行工作区和 checkpoint 备份不进入版本库；Prompt 和 manifest 拒绝 credential 字段。
- `evals/runs/` 整体默认忽略；公开证据必须经过独立脱敏审阅后复制到 `evals/baselines/`，不能直接提交原始运行目录。
- 生产部署还应在宿主防火墙或 Kubernetes NetworkPolicy 层默认拒绝 egress，形成独立于应用参数的第二道边界。

## Dataset v0.3 core

`v0.3-core.1` 已冻结 20 个任务和 3 个固定 fixture。数据集覆盖领域状态、幂等、输入验证、API 异常、查询与 schema、敏感信息、重试、UTC、并发、解析、稳定分页、余额规则与 HMAC 验签。该 manifest 不再原地修改；后续任务或修订必须发布新的 datasetVersion 和 SHA-256。task-021–030 是可选 stretch，不是 v0.3 发布门槛。

## Dataset v0.1

固定 fixture revision：`banking-fixture-v2`。

| Task | 类别 | 确定性目标 |
| --- | --- | --- |
| task-001 | enum | AccountStatus 类型安全枚举 |
| task-002 | validation | 非正数转账在写库前拒绝 |
| task-003 | defect-fix | 非法历史查询时间范围拒绝 |
| task-004 | query-field | TransferQuery 聚合字段 |
| task-005 | transfer-limit | 普通/VIP、UTC 跨日和并发原子限额 |

每个任务包含 `task.yml`、`fixture.ref` 和 `assertions.yml`。Runner 为每个任务复制独立 workspace，并发执行后汇总断言、编译、测试、Recall、步骤、Token、费用、时长和产物路径。

## 基线解释

- `reference-ceiling` 使用不可见于 fixture 索引的参考覆盖层，验证 dataset 与断言本身可达到 5/5；它不是模型成绩。
- `fake-replay` 端到端测试从 baseline 重新建立索引，经过 Planner、审批、4 次受控 patch、Verification Pipeline 和真实 Maven 测试完成 task-005。
- 真实模型基线已使用 OpenAI Codex 的现有 ChatGPT 账户凭据运行：GPT-5.4 首次 1/5，在每任务最多一次公开断言反馈重试后达到 5/5。模型、Prompt/策略版本、价格口径、Token 和环境证据见 [`evals/baselines/2026-08-29-gpt-5.4-codex-agent-v1`](../evals/baselines/2026-08-29-gpt-5.4-codex-agent-v1/README.md)。报告明确区分 reference ceiling、首次模型成绩和带重试策略的最终成绩。

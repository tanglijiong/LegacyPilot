# ANALYZE 与 EXPLAIN 只读任务

LegacyPilot 现在支持三种任务模式。模式不是一套固定脚本，而是任务的安全边界和结果契约：Agent 仍会根据问题与代码索引生成不同计划，但只能在该模式允许的范围内行动。

| 模式 | 适合的问题 | 是否允许修改代码 | 结果重点 |
| --- | --- | --- | --- |
| `CHANGE` | “实现每日转账限额” | 允许，写操作仍受策略和审批控制 | 变更、测试和验证报告 |
| `ANALYZE` | “这个需求要怎么实现，会影响哪些模块？” | 不允许 | 入口、调用链、影响面、规则、数据操作、风险和测试建议 |
| `EXPLAIN` | “现有转账业务是怎样运行的？” | 不允许 | 参与者、触发条件、主流程、决策规则、状态/数据变化、异常流程和术语 |

## 它如何工作

1. 根据需求和检索到的代码生成调查计划，而不是套用预设步骤。
2. 按计划调用代码搜索、文件读取和依赖关系等只读工具。
3. 调查完成后提交结构化结果，每条主要结论都绑定源码路径、符号、行号和 Git revision。
4. Runtime 独立验证必填章节、引用路径、符号、行号、revision 和结论对应关系。
5. Runtime 检查 Git diff 和未跟踪文件；只读任务只要产生工作区变更就不能成功。
6. 最终结果写入运行报告，可通过 REST API 或 CLI 读取。

模型即使请求了写工具，Runtime 也会在审批检查前拒绝执行。已有写授权不能覆盖 `ANALYZE` 或 `EXPLAIN` 的只读边界。

## 创建任务

CLI：

```bash
java -jar apps/cli/target/legacy-pilot-cli-0.3.0-SNAPSHOT.jar \
  task-create \
  --project PROJECT_ID \
  --mode ANALYZE \
  --requirement "分析每日累计转账限额应如何实现，以及会影响哪些组件" \
  --criterion "给出源码位置" \
  --criterion "说明风险和建议测试"
```

REST API：

```bash
curl -X POST http://localhost:8080/api/v1/projects/PROJECT_ID/tasks \
  -H 'Content-Type: application/json' \
  -d '{
    "mode": "EXPLAIN",
    "requirement": "解释转账请求从接口到持久化的完整业务流程",
    "criteria": ["说明正常流程", "说明异常和状态变化"]
  }'
```

省略 `mode` 时默认为 `CHANGE`，因此旧客户端和已有任务保持兼容。

创建运行后，通过统一执行接口启动 Agent：

```bash
curl -X POST http://localhost:8080/api/v1/runs/RUN_ID/execute
```

生产或银行环境需要按照部署配置携带 OIDC 身份；示例省略了环境相关的认证头。

## 读取结果

读取完整报告：

```bash
curl http://localhost:8080/api/v1/runs/RUN_ID/report
```

只读取分析/讲解产物：

```bash
curl http://localhost:8080/api/v1/runs/RUN_ID/artifact
```

使用同一个本地数据目录时，也可通过 CLI 输出 JSON 或适合面试展示的 Markdown：

```bash
java -jar apps/cli/target/legacy-pilot-cli-0.3.0-SNAPSHOT.jar task-result RUN_ID
java -jar apps/cli/target/legacy-pilot-cli-0.3.0-SNAPSHOT.jar \
  task-result RUN_ID --format MARKDOWN
```

产物中的 `sections` 是模式对应的业务结论，`evidence` 是可核对的源码证据，`unknowns` 明确记录代码无法证明的事项；`groundingMetrics` 会给出证据数、结论数、无证据结论数和写入违规数。

## 面试演示建议

选用同一段转账需求连续运行两个任务，最能体现它不是固定工作流：

1. 用 `EXPLAIN` 让它讲清现有转账流程，展示主流程、规则和异常路径。
2. 用 `ANALYZE` 问“增加每日限额应如何实现”，展示不同计划、影响组件、风险和测试建议。
3. 打开任意两条源码证据，现场核对固定 revision、路径、符号、行号和内容哈希；展开上下文证明页面不是展示模型自带的静态摘录。
4. 展示 `unsupportedClaimCount=0` 和 `writeViolationCount=0`。
5. 最后说明：`CHANGE` 负责受控修改；`ANALYZE`/`EXPLAIN` 负责可信地理解代码，三者复用同一个可恢复、可审计 Agent Runtime。

当前交互面包括 CLI、REST API 和 Web 工作台；Web 会通过安全源码 API 读取固定 revision 证据，拒绝任意文件系统路径。

import type { DemoTask, Evidence, ResultSection, RunStep, TaskMode } from "./types";

const REVISION = "d4f150f";

const evidence: Evidence[] = [
  {
    id: "controller-entry",
    path: "samples/banking-demo/src/main/java/io/legacypilot/samples/banking/TransferController.java",
    symbol: "TransferController.transfer",
    lines: "23–27",
    excerpt:
      '@PostMapping\npublic TransferRecord transfer(\n    @PathVariable String accountId, @RequestBody TransferRequest request) {\n  return service.transfer(accountId, request.amount());\n}',
    claim: "POST 转账接口是业务入口，并将账户和金额交给 TransferService。",
    revision: REVISION,
  },
  {
    id: "service-flow",
    path: "samples/banking-demo/src/main/java/io/legacypilot/samples/banking/TransferService.java",
    symbol: "TransferService.transfer",
    lines: "29–34",
    excerpt:
      "public TransferRecord transfer(String accountId, BigDecimal amount) {\n  var transfer =\n      new TransferRecord(UUID.randomUUID().toString(), accountId, amount, clock.instant());\n  repository.save(transfer);\n  return transfer;\n}",
    claim: "当前服务直接创建并保存转账记录，没有每日累计限额校验。",
    revision: REVISION,
  },
  {
    id: "history-query",
    path: "samples/banking-demo/src/main/java/io/legacypilot/samples/banking/TransferRepository.java",
    symbol: "TransferRepository.findByAccountIdAndOccurredAtBetween",
    lines: "11–12",
    excerpt:
      "List<TransferRecord> findByAccountIdAndOccurredAtBetween(\n    String accountId, Instant fromInclusive, Instant toExclusive);",
    claim: "仓储已经支持按账户和时间范围查询转账记录，可作为日累计计算基础。",
    revision: REVISION,
  },
];

const completeSteps: RunStep[] = [
  { label: "定位 HTTP 入口", detail: "找到转账创建和历史查询接口", tool: "search_symbol", state: "DONE" },
  { label: "跟踪调用链", detail: "TransferController → TransferService → TransferRepository", tool: "dependency_graph", state: "DONE" },
  { label: "提取业务规则", detail: "检查金额、客户等级与时间边界", tool: "read_file", state: "DONE" },
  { label: "验证源码证据", detail: "校验路径、符号、行号与 revision", tool: "verify_evidence", state: "DONE" },
];

const analyzeSections: ResultSection[] = [
  {
    title: "入口与调用路径",
    items: [
      { text: "POST 转账接口是业务入口，并将账户和金额交给 TransferService。", evidenceId: "controller-entry" },
      { text: "主要调用链为 Controller → Service → Repository。", evidenceId: "service-flow" },
    ],
  },
  {
    title: "建议实现位置",
    items: [
      { text: "在 TransferService 创建记录之前增加每日累计限额判断。", evidenceId: "service-flow" },
      { text: "复用现有时间范围查询，按 UTC 日界计算累计金额。", evidenceId: "history-query" },
    ],
  },
  {
    title: "风险与建议测试",
    items: [
      { text: "并发请求可能同时通过限额检查，需要原子约束或账户级锁。", evidenceId: "history-query" },
      { text: "覆盖普通/VIP、恰好等于限额、超限、UTC 跨日和并发场景。" },
    ],
  },
];

export const initialTasks: DemoTask[] = [
  {
    id: "task-analyze-limit",
    conversationId: "task-analyze-limit",
    title: "分析每日转账限额",
    prompt: "分析每日累计转账限额应该怎么实现，会影响哪些组件？",
    mode: "ANALYZE",
    status: "SUCCEEDED",
    time: "刚刚",
    steps: completeSteps,
    summary: "改动应集中在服务层和仓储查询，并重点处理并发一致性与 UTC 日界。",
    sections: analyzeSections,
    evidence,
    unknowns: ["当前 fixture 未定义 VIP 客户等级的权威来源，需要产品或领域负责人确认。"],
    metrics: { evidence: 3, statements: 7, unsupported: 0, writes: 0, tokens: 2840, duration: "18.4s" },
  },
  {
    id: "task-explain-flow",
    conversationId: "task-explain-flow",
    title: "讲解现有转账流程",
    prompt: "请给我讲清楚一笔转账从接口到保存记录的完整业务流程。",
    mode: "EXPLAIN",
    status: "SUCCEEDED",
    time: "12 分钟前",
    steps: completeSteps.map((step) => ({ ...step })),
    summary: "转账由 HTTP 接口触发，服务创建带当前时间的记录并交由仓储保存。",
    sections: [
      {
        title: "参与者与触发条件",
        items: [{ text: "客户端调用账户下的 POST 转账接口。", evidenceId: "controller-entry" }],
      },
      {
        title: "主流程",
        items: [
          { text: "Controller 提取 accountId 和 amount，并调用服务。", evidenceId: "controller-entry" },
          { text: "Service 生成 ID 和时间，保存记录后返回。", evidenceId: "service-flow" },
        ],
      },
      {
        title: "当前业务边界",
        items: [{ text: "代码中尚未出现余额、限额或客户等级校验。", evidenceId: "service-flow" }],
      },
    ],
    evidence: evidence.slice(0, 2),
    unknowns: ["仓储接口的具体数据库实现不在当前 fixture 中。"],
    metrics: { evidence: 2, statements: 4, unsupported: 0, writes: 0, tokens: 1960, duration: "12.1s" },
  },
  {
    id: "task-change-limit",
    conversationId: "task-change-limit",
    title: "实施每日转账限额",
    prompt: "实现普通用户 5 万、VIP 20 万的每日累计转账限额。",
    mode: "CHANGE",
    status: "WAITING",
    time: "昨天",
    steps: [
      ...completeSteps.slice(0, 2).map((step) => ({ ...step })),
      { label: "准备修改服务层", detail: "将修改 3 个文件并补充 5 组测试", tool: "apply_patch", state: "RUNNING" },
      { label: "执行 Maven 验证", detail: "等待变更审批", tool: "maven_test", state: "PENDING" },
    ],
    summary: "高风险写操作正在等待独立审批。",
    sections: [
      {
        title: "待审批变更",
        items: [
          { text: "修改 TransferService，在保存记录前执行限额校验。", evidenceId: "service-flow" },
          { text: "扩展仓储并新增边界与并发测试。", evidenceId: "history-query" },
        ],
      },
    ],
    evidence: evidence.slice(1),
    unknowns: [],
    metrics: { evidence: 2, statements: 2, unsupported: 0, writes: 0, tokens: 3140, duration: "等待审批" },
  },
];

export const modeMeta: Record<TaskMode, { label: string; short: string; description: string }> = {
  EXPLAIN: { label: "讲解业务", short: "解释", description: "阅读代码并讲清现有业务，不修改项目" },
  ANALYZE: { label: "分析需求", short: "分析", description: "定位实现位置、影响范围和风险，不修改项目" },
  CHANGE: { label: "修改代码", short: "变更", description: "规划并实施修改，风险操作需要审批" },
};

export function makeDemoTask(mode: TaskMode, prompt: string): DemoTask {
  const id = `task-${Date.now()}`;
  const base = initialTasks[mode === "EXPLAIN" ? 1 : mode === "CHANGE" ? 2 : 0];
  return {
    ...base,
    id,
    title: prompt.length > 22 ? `${prompt.slice(0, 22)}…` : prompt,
    prompt,
    mode,
    status: "RUNNING",
    time: "刚刚",
    steps: base.steps.map((step, index) => ({
      ...step,
      state: index === 0 ? "RUNNING" : "PENDING",
    })),
    summary: "Agent 正在根据问题生成调查计划。",
    sections: [],
    unknowns: [],
    metrics: { evidence: 0, statements: 0, unsupported: 0, writes: 0, tokens: 0, duration: "运行中" },
  };
}

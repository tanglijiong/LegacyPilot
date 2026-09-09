import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  AlertTriangle, ArrowUpRight, Bot, Braces, Check, CheckCircle2, ChevronDown, Circle,
  Clock3, Code2, FileCode2, GitBranch, ListTree, LoaderCircle, Menu, MessageSquareText,
  FolderOpen, FolderPlus, PanelRightClose, PanelRightOpen, Play, Plus, RefreshCw, Search, Send,
  Settings, ShieldCheck, Sparkles, X, Zap, Ban, RotateCcw, Trash2, Wrench,
  FileDiff, ShieldAlert,
} from "lucide-react";
import { ApiError, LegacyPilotApi } from "./api";
import { modeMeta } from "./demoData";
import type {
  ApiArtifact, ApiCheckpoint, ApiProject, ApiReport, ApiRun, ApiRunEvent, ApiTaskSummary,
  ApiSourceSnippet, ChangeReview, ConnectionSettings, DemoTask, Evidence, ProjectPreflight, TaskMode,
} from "./types";
import "./styles.css";

const SETTINGS_KEY = "legacypilot.web.connection.v1";
const ACTIVE_PROJECT_KEY = "legacypilot.web.active-project.v1";
const RUNNING_STATUSES = new Set(["CREATED", "PREPARING_WORKSPACE", "WORKSPACE_READY", "QUEUED", "PLANNING", "EXECUTING", "VERIFYING", "RECOVERING"]);
const DIRECTORY_UPLOAD_IGNORED = new Set([".git", ".gradle", ".idea", ".next", ".cache", "node_modules", "target", "build", "dist", "out", "coverage", "vendor"]);
const MAX_DIRECTORY_FILE_BYTES = 64 * 1024 * 1024;
const MAX_DIRECTORY_TOTAL_BYTES = 256 * 1024 * 1024;

function defaultSettings(): ConnectionSettings {
  try {
    const saved = sessionStorage.getItem(SETTINGS_KEY);
    if (saved) return JSON.parse(saved) as ConnectionSettings;
  } catch { /* use defaults */ }
  return { apiBaseUrl: import.meta.env.VITE_API_BASE_URL ?? "", developerToken: "", approverToken: "" };
}

function runCode(id?: string) {
  if (!id) return "尚未运行";
  return id.length > 13 ? `${id.slice(0, 7)}…${id.slice(-5)}` : id;
}

function displayTime(value: string) {
  const elapsed = Date.now() - new Date(value).getTime();
  if (elapsed < 60_000) return "刚刚";
  if (elapsed < 3_600_000) return `${Math.floor(elapsed / 60_000)} 分钟前`;
  if (elapsed < 86_400_000) return `${Math.floor(elapsed / 3_600_000)} 小时前`;
  return new Date(value).toLocaleDateString("zh-CN");
}

function durationLabel(value?: string | number) {
  if (typeof value === "number") return `${value.toFixed(1)}s`;
  if (!value) return "运行中";
  const match = /^PT(?:(\d+)M)?([\d.]+)S$/.exec(value);
  return match ? `${match[1] ? `${match[1]}m ` : ""}${Number(match[2]).toFixed(1)}s` : value;
}

function runtimeMessage(value?: string) {
  if (!value) return "运行失败，尚未返回具体原因";
  if (value.startsWith("model failure: INVALID_RESPONSE")) {
    return "模型未能生成有效的结构化响应；系统已停止当前操作，请重新尝试。";
  }
  const messages: Record<string, string> = {
    BUDGET_EXHAUSTED: "运行预算已耗尽",
    "retry budget exhausted": "模型连续生成无效操作，重试次数已用完",
    "step budget exhausted": "执行步骤已达到安全上限",
    "token budget exhausted": "模型上下文用量已达到安全上限",
    "cost budget exhausted": "模型调用费用已达到安全上限",
    "duration budget exhausted": "运行时间已达到安全上限",
    "repeated failed action detected": "同一操作连续失败，系统已停止自动重试",
    "required verification failed": "代码变更未通过必要验证",
  };
  return messages[value] ?? value;
}

function taskStatus(run?: ApiRun): DemoTask["status"] {
  if (!run) return "WAITING";
  if (RUNNING_STATUSES.has(run.status)) return "RUNNING";
  if (run.status === "SUCCEEDED") return "SUCCEEDED";
  if (run.status === "WAITING_FOR_APPROVAL" || run.status === "NEEDS_REVIEW") return "WAITING";
  return "FAILED";
}

function asEvidence(artifact?: ApiArtifact): Evidence[] {
  return (artifact?.evidence ?? []).map((item, index) => ({
    id: `evidence-${index}-${item.startLine}`,
    path: item.path,
    symbol: item.symbol,
    lines: item.startLine === item.endLine ? `${item.startLine}` : `${item.startLine}–${item.endLine}`,
    excerpt: item.excerpt,
    claim: item.claim,
    revision: item.revision,
    excerptSha256: item.excerptSha256,
  }));
}

function asSections(artifact: ApiArtifact | undefined, report: ApiReport | undefined, evidence: Evidence[]) {
  if (artifact) {
    return Object.entries(artifact.sections).map(([title, items]) => ({
      title,
      items: items.map((text) => ({ text, evidenceId: evidence.find((item) => item.claim === text)?.id })),
    }));
  }
  if (report?.verification.length) {
    return [{
      title: report.verification.every((item) => item.required !== "true" || item.status === "PASSED") ? "验证通过" : "验证未通过",
      items: report.verification.map((item) => {
        const required = item.required === "true";
        const status = item.status === "PASSED" ? "通过" : required ? "失败" : "非阻断告警";
        const names: Record<string, string> = {
          workspace_integrity: "工作区安全",
          "diff-policy": "变更范围",
          compile: "编译",
          tests: "测试",
          "static-analysis": "静态分析",
        };
        return { text: `${names[item.name] ?? item.name}：${status}。${item.evidence ?? ""}` };
      }),
    }];
  }
  return [];
}

async function hydrateTask(api: LegacyPilotApi, task: ApiTaskSummary): Promise<DemoTask> {
  const run = task.latestRun;
  let checkpoint: ApiCheckpoint | undefined;
  let report: ApiReport | undefined;
  let artifact: ApiArtifact | undefined;
  let approval: DemoTask["approval"];
  let changeReview: ChangeReview | undefined;
  let recovery: DemoTask["recovery"];
  if (run) {
    const recoveryResult = await Promise.allSettled([api.getRecovery(run.id)]);
    if (recoveryResult[0].status === "fulfilled") recovery = recoveryResult[0].value;
  }
  if (run && !["CREATED", "PREPARING_WORKSPACE", "WORKSPACE_READY"].includes(run.status)) {
    const checkpointResult = await Promise.allSettled([api.getCheckpoint(run.id), api.getReport(run.id)]);
    if (checkpointResult[0].status === "fulfilled") checkpoint = checkpointResult[0].value;
    if (checkpointResult[1].status === "fulfilled") report = checkpointResult[1].value;
    artifact = report?.artifact;
    if (run.status === "SUCCEEDED" && task.mode !== "CHANGE") {
      const artifactResult = await Promise.allSettled([api.getArtifact(run.id)]);
      if (artifactResult[0].status === "fulfilled") artifact = artifactResult[0].value;
    }
    if (run.status === "WAITING_FOR_APPROVAL") {
      const approvalResult = await Promise.allSettled([api.getApprovalContext(run.id)]);
      if (approvalResult[0].status === "fulfilled") approval = approvalResult[0].value;
    }
    if (task.mode === "CHANGE") {
      const reviewResult = await Promise.allSettled([api.getChangeReview(run.id)]);
      if (reviewResult[0].status === "fulfilled") changeReview = reviewResult[0].value;
      if (!approval && changeReview?.action && run.status === "WAITING_FOR_APPROVAL") approval = changeReview.action;
    }
  }
  const evidence = asEvidence(artifact);
  const plan = checkpoint?.plan?.steps ?? report?.plan ?? run?.history.map((item) => item.reason) ?? ["等待创建运行"];
  const status = taskStatus(run);
  const completed = status === "SUCCEEDED" ? plan.length : 0;
  const pendingTool = checkpoint?.pendingAction?.tool;
  const steps = plan.map((label, index) => ({
    label,
    detail: index === 0 && checkpoint?.observation ? runtimeMessage(checkpoint.observation) : status === "SUCCEEDED" ? "已完成" : "计划项；实际动作请查看运行记录",
    tool: index === completed && pendingTool ? pendingTool : "agent",
    state: (status === "SUCCEEDED" ? "DONE" : index === 0 && status === "RUNNING" ? "RUNNING" : "PENDING") as "DONE" | "RUNNING" | "PENDING",
  }));
  const metrics = report?.groundingMetrics ?? {};
  return {
    id: task.id,
    conversationId: task.conversationId ?? task.id,
    projectId: task.projectId,
    runId: run?.id,
    title: task.requirement.length > 28 ? `${task.requirement.slice(0, 28)}…` : task.requirement,
    prompt: task.requirement,
    mode: task.mode,
    status,
    rawStatus: run?.status ?? "NO_RUN",
    runVersion: run?.version,
    time: displayTime(run?.updatedAt ?? task.createdAt),
    steps,
    summary: runtimeMessage(artifact?.summary ?? report?.summary ?? run?.terminalReason ?? (run ? `运行状态：${run.status}` : "任务尚未创建运行")),
    sections: asSections(artifact, report, evidence),
    evidence,
    unknowns: artifact?.unknowns ?? [],
    approval,
    changeReview,
    recovery,
    metrics: {
      evidence: Number(metrics.evidenceCount ?? evidence.length),
      statements: Number(metrics.statementCount ?? 0),
      unsupported: Number(metrics.unsupportedClaimCount ?? 0),
      writes: Number(metrics.writeViolationCount ?? 0),
      tokens: report?.modelTokens ?? (checkpoint ? checkpoint.usage.inputTokens + checkpoint.usage.outputTokens : 0),
      duration: durationLabel(report?.duration),
    },
  };
}

function ModeBadge({ mode }: { mode: TaskMode }) {
  return <span className={`mode-badge mode-${mode.toLowerCase()}`}>{modeMeta[mode].short}</span>;
}

function StatusDot({ task }: { task: DemoTask }) {
  if (task.status === "SUCCEEDED") return <CheckCircle2 className="status-icon success" />;
  if (task.status === "WAITING") return <AlertTriangle className="status-icon waiting" />;
  if (task.status === "FAILED") return <Circle className="status-icon danger" />;
  return <LoaderCircle className="status-icon running spin" />;
}

function projectName(project?: ApiProject) {
  if (!project) return "尚未选择项目";
  if (project.source.startsWith("directory://")) {
    return project.source.split("/").at(-1) || "本地目录";
  }
  const segments = project.repositoryPath.replace(/\/$/, "").split("/");
  return segments.at(-1) || project.source;
}

function Sidebar({ projects, project, tasks, selectedId, onProject, onSelect, onNew, onOnboard, onSettings, open, onClose }: {
  projects: ApiProject[]; project?: ApiProject; tasks: DemoTask[]; selectedId?: string;
  onProject: (id: string) => void; onSelect: (id: string) => void; onNew: () => void; onOnboard: () => void; onSettings: () => void; open: boolean; onClose: () => void;
}) {
  return <aside className={`sidebar ${open ? "mobile-open" : ""}`}>
    <div className="brand-row"><div className="brand-mark"><GitBranch size={18} /></div><div><strong>LegacyPilot</strong><span>Agent Workspace</span></div><button className="icon-button mobile-only" onClick={onClose} aria-label="关闭任务列表"><X size={18} /></button></div>
    <button className="new-task-button" onClick={onNew} disabled={!project}><Plus size={17} />新建任务</button>
    <div className="project-label"><span>项目</span><button className="project-add" onClick={onOnboard} aria-label="接入项目" title="打开本地目录或接入 Git 仓库"><FolderPlus size={15} /></button></div>
    <div className="project-switcher"><label className="project-card active-project"><span className="repo-icon"><Braces size={17} /></span><span><strong>{projectName(project)}</strong><small>{project ? `${project.baseRevision.slice(0, 7)} · 真实 API` : "请连接后端"}</small></span><ChevronDown size={15} /><select aria-label="选择项目" value={project?.id ?? ""} onChange={(event) => onProject(event.target.value)}><option value="" disabled>选择项目</option>{projects.map((item) => <option key={item.id} value={item.id}>{projectName(item)}</option>)}</select></label><button className="project-remove" onClick={() => onProject("__remove_current_project__")} disabled={!project} aria-label="移除当前项目" title="从工作台移除当前项目"><Trash2 size={15} /></button></div>
    <div className="task-heading"><span>最近任务</span><Search size={15} /></div>
    <nav className="task-list" aria-label="对话列表">{tasks.map((task) => <button key={task.conversationId} className={`task-item ${task.conversationId === selectedId ? "selected" : ""}`} onClick={() => { onSelect(task.id); onClose(); }}><StatusDot task={task} /><span className="task-copy"><strong>{task.title}</strong><span><ModeBadge mode={task.mode} /><small>{task.time}</small></span></span></button>)}</nav>
    <div className="sidebar-footer"><div className="avatar">API</div><span><strong>真实后端</strong><small>凭据仅保存在当前标签页</small></span><button className="icon-button" onClick={onSettings} aria-label="连接设置"><Settings size={16} /></button></div>
  </aside>;
}

function Topbar({ task, project, connected, onMenu, onRefresh, onSettings }: { task?: DemoTask; project?: ApiProject; connected: boolean; onMenu: () => void; onRefresh: () => void; onSettings: () => void }) {
  return <header className="topbar"><button className="icon-button mobile-only" onClick={onMenu} aria-label="打开任务列表"><Menu size={19} /></button><div className="breadcrumb"><span>{projectName(project)}</span><span>/</span><strong>{task?.title ?? "工作台"}</strong></div><div className="topbar-meta"><span className="runtime-chip"><span className={`online-dot ${connected ? "" : "offline"}`} />{connected ? "API 已连接" : "API 未连接"}</span><span className="revision"><GitBranch size={14} />{project?.baseRevision.slice(0, 7) ?? "—"}</span><button className="icon-button" onClick={onRefresh} aria-label="刷新任务"><RefreshCw size={17} /></button><button className="icon-button" onClick={onSettings} aria-label="连接设置"><Settings size={17} /></button></div></header>;
}

function TaskHeader({ task, title, turns }: { task: DemoTask; title: string; turns: number }) {
  return <section className="task-header"><div className="eyebrow-row"><ModeBadge mode={task.mode} /><span>RUN {runCode(task.runId)}</span><span>{task.rawStatus}</span></div><div className="conversation-title"><div><span>项目对话 · {turns} 轮</span><h1>{title}</h1></div><MessageSquareText size={22} /></div>{task.mode !== "CHANGE" && <div className="read-only-banner"><ShieldCheck size={17} /><strong>只读保护已开启</strong><span>追问会保留在同一对话中，并继续基于当前项目的固定快照分析</span></div>}</section>;
}

function Timeline({ task }: { task: DemoTask }) {
  const progress = task.status === "SUCCEEDED" ? `${task.steps.length}/${task.steps.length} 步` : `计划 ${task.steps.length} 项`;
  return <section className="content-card timeline-card"><div className="section-title"><div><ListTree size={18} /><h2>执行计划</h2></div><span>{progress}</span></div><div className="timeline">{task.steps.map((step, index) => <div className={`timeline-step step-${step.state.toLowerCase()}`} key={`${task.id}-${index}`}><div className="step-rail"><span className="step-node">{step.state === "DONE" ? <Check size={13} /> : step.state === "RUNNING" ? <LoaderCircle size={13} className="spin" /> : <span />}</span>{index < task.steps.length - 1 && <span className="step-line" />}</div><div className="step-content"><strong>{step.label}</strong><p>{step.detail}</p></div><code>{step.tool}</code></div>)}</div></section>;
}

function Metrics({ task }: { task: DemoTask }) {
  const changeFiles = task.changeReview?.diff.length ?? 0;
  const changeLines = task.changeReview?.diff.reduce((total, file) => total + file.additions + file.deletions, 0) ?? 0;
  const requiredPassed = task.changeReview?.verification.filter((check) => check.required === "true" && check.status === "PASSED").length ?? 0;
  const warnings = task.changeReview?.verification.filter((check) => check.required !== "true" && check.status !== "PASSED").length ?? 0;
  const items = task.mode === "CHANGE"
    ? [["变更文件", changeFiles, "good"], ["变更行数", changeLines, "good"], ["必需检查通过", requiredPassed, "good"], ["非阻断告警", warnings, warnings ? "warn" : "good"]] as const
    : [["源码证据", task.metrics.evidence, "good"], ["有依据结论", task.metrics.statements, "good"], ["无依据结论", task.metrics.unsupported, task.metrics.unsupported ? "bad" : "good"], ["写入违规", task.metrics.writes, task.metrics.writes ? "bad" : "good"]] as const;
  return <div className="metric-grid">{items.map(([label, value, tone]) => <div className="metric" key={label}><span>{label}</span><strong className={tone}>{value}</strong></div>)}</div>;
}

function ResultView({ task, onEvidence, onFollowUp }: { task: DemoTask; onEvidence: (id: string) => void; onFollowUp: () => void }) {
  if (task.status === "RUNNING") return <section className="content-card result-loading"><div className="agent-orbit"><Bot size={22} /><span /></div><h2>Agent 正在运行</h2><p>实时事件流正在更新步骤；连接中断时自动切换为轮询。</p></section>;
  return <section className="content-card result-card"><div className="result-heading"><div className="result-icon"><Sparkles size={19} /></div><div><span>LegacyPilot 结论</span><h2>{task.summary}</h2></div></div><Metrics task={task} />{task.sections.map((section) => <div className="result-section" key={section.title}><h3>{section.title}</h3><ul>{section.items.map((item, index) => <li key={`${section.title}-${index}`}><span>{item.text}</span>{item.evidenceId && <button onClick={() => onEvidence(item.evidenceId!)}>查看证据 <ArrowUpRight size={13} /></button>}</li>)}</ul></div>)}{task.unknowns.length > 0 && <div className="unknowns"><AlertTriangle size={17} /><div><strong>需要确认</strong>{task.unknowns.map((item) => <p key={item}>{item}</p>)}</div></div>}<div className="result-footer"><span><Clock3 size={14} />{task.metrics.duration}</span><span><Zap size={14} />{task.metrics.tokens.toLocaleString()} tokens</span><button onClick={onFollowUp}><MessageSquareText size={15} />基于结果继续提问</button></div></section>;
}

function ExecutionDetails({ task, events, live, recoveryBusy, approvalBusy, onRecover, onDecision, onApply, defaultTarget }: {
  task: DemoTask; events: ApiRunEvent[]; live: boolean; recoveryBusy: boolean; approvalBusy: boolean;
  onRecover: (action: "CANCEL" | "RETRY_CURRENT_STEP" | "REPLAN") => void;
  onDecision: (approved: boolean, reason: string) => void;
  onApply: (targetPath: string) => Promise<string>;
  defaultTarget: string;
}) {
  const progress = task.status === "SUCCEEDED" ? `${task.steps.length}/${task.steps.length} 步` : `计划 ${task.steps.length} 项`;
  const active = task.status !== "SUCCEEDED" || task.mode === "CHANGE";
  return <details className="content-card execution-details" open={active}><summary><div><ListTree size={17} /><span><strong>执行详情</strong><small>变更差异、验证、计划与运行记录</small></span></div><span>{progress} <ChevronDown size={15} /></span></summary><div className="execution-details-body">{task.mode === "CHANGE" && <ChangeWorkbench task={task} busy={approvalBusy} onDecision={onDecision} onApply={onApply} defaultTarget={defaultTarget} />}<Timeline task={task} />{task.runId && <RunLog events={events} live={live} />}<RecoveryPanel task={task} busy={recoveryBusy} onAction={onRecover} /></div></details>;
}

function ConversationComposer({ mode, onMode, onSubmit, busy, disabled }: { mode: TaskMode; onMode: (mode: TaskMode) => void; onSubmit: (prompt: string) => void; busy: boolean; disabled: boolean }) {
  const [prompt, setPrompt] = useState("");
  const send = () => { const value = prompt.trim(); if (!value || busy || disabled) return; setPrompt(""); onSubmit(value); };
  return <section className="conversation-composer" aria-label="继续对话"><div className="conversation-mode">{(Object.keys(modeMeta) as TaskMode[]).map((item) => <button key={item} className={mode === item ? "active" : ""} onClick={() => onMode(item)} aria-pressed={mode === item}>{modeMeta[item].label}</button>)}</div><div className="conversation-input"><textarea aria-label="继续对话" value={prompt} onChange={(event) => setPrompt(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); send(); } }} placeholder="继续追问这个项目，Enter 发送，Shift + Enter 换行" rows={2} disabled={disabled} /><button onClick={send} disabled={busy || disabled || !prompt.trim()} aria-label="发送追问"><Send size={17} /></button></div><p>{mode === "CHANGE" ? "将创建受治理的变更轮次，高风险动作仍需独立审批。" : "追问会成为同一对话的新轮次，并保留可回链的运行与源码证据。"}</p></section>;
}

function RunLog({ events, live }: { events: ApiRunEvent[]; live: boolean }) {
  return <details className="content-card run-log" open={live}>
    <summary><span><Clock3 size={17} />运行记录</span><span className={`stream-state ${live ? "live" : "fallback"}`}>{live ? "实时" : "轮询降级"}</span></summary>
    <div className="run-event-list" role="log" aria-label="运行事件" tabIndex={0}>{events.length ? events.map((event) => <div className="run-event" key={event.sequence}><code>#{event.sequence}</code><div><strong>{event.summary}</strong><span>{event.phase} · {new Date(event.time).toLocaleTimeString("zh-CN")}{event.correlationId ? ` · ${event.correlationId.slice(0, 12)}…` : ""}</span></div></div>) : <p>等待第一条运行事件…</p>}</div>
  </details>;
}

function RecoveryPanel({ task, busy, onAction }: { task: DemoTask; busy: boolean; onAction: (action: "CANCEL" | "RETRY_CURRENT_STEP" | "REPLAN") => void }) {
  const recovery = task.recovery;
  if (!recovery || task.rawStatus === "SUCCEEDED") return null;
  const labels: Record<string, string> = {
    NONE: "运行正常", USER_CANCELLED: "用户取消", MODEL_FAILURE: "模型失败",
    TOOL_FAILURE: "工具失败", POLICY_BLOCK: "策略阻断", VERIFICATION_FAILURE: "验证失败",
    INFRASTRUCTURE_FAILURE: "基础设施失败",
  };
  const actionable = recovery.allowedActions.some((item) => ["CANCEL", "RETRY_CURRENT_STEP", "REPLAN", "HUMAN_REVIEW", "WAIT_FOR_APPROVAL"].includes(item));
  if (!actionable && recovery.attempts.length < 2) return null;
  return <section className="content-card recovery-panel">
    <div className="recovery-heading"><div><Wrench size={17} /><strong>运行恢复</strong><span>{labels[recovery.failureCategory]}</span></div><code>version {recovery.expectedVersion}</code></div>
    <div className="recovery-diagnosis"><div><span>诊断</span><p>{runtimeMessage(recovery.diagnosis)}</p></div><div><span>重放安全性</span><strong className={recovery.replaySafety === "SAFE_FROM_CHECKPOINT" ? "safe" : "manual"}>{recovery.replaySafety === "SAFE_FROM_CHECKPOINT" ? "可从安全 checkpoint 恢复" : "需要人工复核，不提供自动重试"}</strong></div></div>
    <div className="recovery-actions">
      {recovery.allowedActions.includes("WAIT_FOR_APPROVAL") && <span className="action-hint">等待独立审批后继续</span>}
      {recovery.allowedActions.includes("HUMAN_REVIEW") && <span className="action-hint manual">动作结果不确定，请先人工核对 workspace 与外部副作用</span>}
      {recovery.allowedActions.includes("CANCEL") && <button className="secondary danger-button" disabled={busy} onClick={() => onAction("CANCEL")}><Ban size={14} />取消运行</button>}
      {recovery.allowedActions.includes("RETRY_CURRENT_STEP") && <button className="secondary" disabled={busy} onClick={() => onAction("RETRY_CURRENT_STEP")}><RotateCcw size={14} />重试当前步骤</button>}
      {recovery.allowedActions.includes("REPLAN") && <button className="primary" disabled={busy} onClick={() => onAction("REPLAN")}><RefreshCw size={14} />重新规划</button>}
    </div>
    <details className="attempt-history"><summary>查看 {recovery.attempts.length} 次持久化 attempt 与诊断</summary><div>{recovery.attempts.map((attempt) => <article key={attempt.commandId}><strong>#{attempt.number} · {attempt.operation}</strong><span>{attempt.status} · worker claim {attempt.workerClaims}</span><code>{attempt.correlationId}</code>{attempt.error && <p>{attempt.error}</p>}</article>)}</div></details>
  </section>;
}

function ChangeWorkbench({ task, busy, onDecision, onApply, defaultTarget }: { task: DemoTask; busy: boolean; onDecision: (approved: boolean, reason: string) => void; onApply: (targetPath: string) => Promise<string>; defaultTarget: string }) {
  const review = task.changeReview;
  const context = task.approval;
  const [reason, setReason] = useState("");
  const [targetPath, setTargetPath] = useState(defaultTarget);
  const [applying, setApplying] = useState(false);
  const [applicationMessage, setApplicationMessage] = useState("");
  const [applicationError, setApplicationError] = useState("");
  useEffect(() => { setTargetPath(defaultTarget); }, [defaultTarget, task.runId]);
  if (!review) return task.rawStatus === "WAITING_FOR_APPROVAL" ? <section className="approval-card"><LoaderCircle className="spin" size={18} />正在生成服务端决策包…</section> : null;
  const pending = task.rawStatus === "WAITING_FOR_APPROVAL" && context;
  const categories = [
    ["COMPILE", "编译"], ["TEST", "测试"], ["STATIC_ANALYSIS", "安全与静态检查"], ["COVERAGE", "覆盖率"],
  ] as const;
  return <section className="content-card change-workbench">
    <div className="review-heading"><div><ShieldAlert size={19} /><span>CHANGE 决策工作台</span></div><strong className={`risk risk-${review.risk.toLowerCase()}`}>风险 {review.risk}</strong></div>
    <div className="review-summary"><div><span>为什么改</span><p>{review.rationale}</p></div><div><span>服务端可信摘要</span><p>{review.action?.summary ?? (review.decision ? `操作已${review.decision.decision === "APPROVED" ? "批准" : "拒绝"}` : "执行已结束")}</p></div></div>
    <div className="review-grid"><div><h3>执行计划 · v{review.planVersion}</h3><ol>{review.steps.map((step) => <li key={step}>{step}</li>)}</ol></div><div><h3>预计影响文件</h3><ul>{review.affectedFiles.map((file) => <li key={file}><FileCode2 size={13} />{file}</li>)}</ul></div></div>
    <div className="policy-row">{review.policyChecks.map((check) => <div key={check.name}><ShieldCheck size={14} /><span>{check.name}</span><strong>{check.status}</strong><small>{check.detail}</small></div>)}</div>
    <div className="diff-section"><h3><FileDiff size={16} />Git Diff · 文件与行级差异</h3>{review.diff.length ? review.diff.map((file) => <details key={file.path} open><summary><code>{file.path}</code><span className="diff-stat">+{file.additions} −{file.deletions}</span></summary>{file.error ? <p className="diff-error">{file.error}</p> : <pre className="diff-view">{file.lines.map((line, index) => <span className={`diff-${line.kind.toLowerCase()}`} key={`${line.oldLine}-${line.newLine}-${index}`}><i>{line.oldLine ?? ""}</i><i>{line.newLine ?? ""}</i><b>{line.kind === "ADD" ? "+" : line.kind === "DELETE" ? "−" : " "}</b><code>{line.text || " "}</code></span>)}</pre>}{file.truncated && <p className="diff-error">差异超过展示上限，必须缩小范围后再审批。</p>}</details>) : <p className="diff-error">当前运行没有检测到文件差异。</p>}</div>
    <div className="verification-section"><h3>验证结果</h3><div className="verification-grid">{categories.map(([category, label]) => {
      const checks = review.verification.filter((item) => item.category === category);
      return <div key={category}><span>{label}</span>{checks.length ? checks.map((check) => {
        const optionalWarning = check.required !== "true" && check.status !== "PASSED";
        const status = optionalWarning ? "非阻断告警" : check.status === "PASSED" ? "通过" : "失败";
        let summary = check.summary;
        if (check.name === "static-analysis" && optionalWarning) {
          const bugs = /Total bugs:\s*(\d+)/.exec(summary)?.[1];
          summary = `Checkstyle 已通过；SpotBugs 发现${bugs ? ` ${bugs} 个` : ""}存量问题，不影响本次任务通过。`;
        }
        const names: Record<string, string> = { workspace_integrity: "工作区安全", "diff-policy": "变更范围", "static-analysis": "代码质量" };
        return <p key={`${category}-${check.name}`}><strong className={optionalWarning ? "check-warning" : `check-${check.status.toLowerCase()}`}>{names[check.name] ?? check.name} · {status}</strong>{summary}</p>;
      }) : <p><strong className="check-pending">未配置</strong>{category === "COVERAGE" ? "项目未配置独立覆盖率门禁，不以普通测试结果冒充覆盖率。" : "批准写入后执行"}</p>}</div>;
    })}</div></div>
    {task.rawStatus === "SUCCEEDED" && <div className="apply-source"><div><h3>应用到原项目</h3><p>将上方已审批并验证通过的 Git Diff 写回 IDEA 当前打开的项目。写入前会逐文件核对任务基线；检测到本地修改时会拒绝覆盖。</p></div><label><span>本地项目绝对路径</span><input value={targetPath} onChange={(event) => setTargetPath(event.target.value)} placeholder="/Users/name/workspace/project" /></label>{applicationError && <p className="apply-error">{applicationError}</p>}{applicationMessage && <p className="apply-success">{applicationMessage}</p>}<button className="primary" disabled={applying || !targetPath.trim()} onClick={() => { setApplying(true); setApplicationError(""); void onApply(targetPath).then(setApplicationMessage).catch((cause: Error) => setApplicationError(cause.message)).finally(() => setApplying(false)); }}>{applying ? "正在核对并应用…" : "应用到原项目"}</button></div>}
    {review.decision && <div className={`decision-banner decision-${review.decision.decision.toLowerCase()}`}><ShieldCheck size={17} /><div><strong>{review.decision.decision === "APPROVED" ? "已由独立审批人批准" : "已拒绝，受治理写操作已终止"}</strong><p>{review.decision.actor} · {review.decision.reason || "未填写说明"}</p><code>action {review.decision.actionDigest.slice(0, 12)}… · plan {review.decision.planDigest.slice(0, 12)}…</code>{review.decision.decision === "DENIED" && <small>如需继续，请创建后续 CHANGE 任务重新规划；旧审批不会复用。</small>}</div></div>}
    {pending && <div className="approval-decision"><label><span>审批说明 / 拒绝理由</span><textarea value={reason} onChange={(event) => setReason(event.target.value)} placeholder="记录判断依据；拒绝时必填" maxLength={1000} /></label><div><code>action {context.actionDigest.slice(0, 12)}…<br />plan {context.planDigest.slice(0, 12)}…</code><button className="secondary" disabled={busy || !reason.trim()} onClick={() => onDecision(false, reason.trim())}>拒绝并终止</button><button className="primary" disabled={busy} onClick={() => onDecision(true, reason.trim() || "Web 工作台独立复核通过")}>{busy ? "提交中…" : "批准并继续"}</button></div></div>}
  </section>;
}

function EvidencePanel({ evidence, projectId, api, onClose }: { evidence?: Evidence; projectId?: string; api: LegacyPilotApi; onClose: () => void }) {
  const [source, setSource] = useState<ApiSourceSnippet>();
  const [sourceError, setSourceError] = useState<string>();
  const [contextLines, setContextLines] = useState(5);
  const [fullFile, setFullFile] = useState(false);
  const [loading, setLoading] = useState(false);
  const [evidenceStart, evidenceEnd] = (evidence?.lines ?? "1").split("–").map(Number);
  useEffect(() => { setContextLines(5); setFullFile(false); }, [evidence?.id]);
  useEffect(() => {
    if (!evidence || !projectId) { setSource(undefined); setSourceError(undefined); return; }
    let active = true;
    setLoading(true);
    setSourceError(undefined);
    void api.getSource(projectId, evidence.revision, evidence.path, evidenceStart, evidenceEnd ?? evidenceStart, contextLines, fullFile)
      .then((result) => { if (active) setSource(result); })
      .catch((cause: Error) => { if (active) { setSource(undefined); setSourceError(cause.message); } })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [api, projectId, evidence?.id, contextLines, fullFile]);
  const verified = Boolean(source && source.revision.toLowerCase() === evidence?.revision.toLowerCase() && source.content.includes(evidence?.excerpt ?? "") && (!evidence?.excerptSha256 || source.requestedRangeSha256 === evidence.excerptSha256));
  const copyPath = () => { if (evidence) void navigator.clipboard.writeText(evidence.path); };
  return <aside className={`evidence-panel ${evidence ? "visible" : ""}`}><div className="evidence-top"><div><span>源码证据</span><strong>{evidence?.symbol ?? "选择一条结论"}</strong></div><button className="icon-button" onClick={onClose} aria-label="关闭源码证据"><PanelRightClose size={18} /></button></div>{evidence ? <><button className="evidence-file" onClick={copyPath} title="复制仓库相对路径"><FileCode2 size={16} /><div><strong>{evidence.path.split("/").at(-1)}</strong><span>{evidence.path}</span></div></button><div className="source-meta"><span>证据行 {evidence.lines}</span><span>revision {evidence.revision.slice(0, 12)}</span></div>{loading ? <div className="source-loading"><LoaderCircle className="spin" size={18} />正在读取固定 revision…</div> : sourceError ? <div className="source-error"><AlertTriangle size={16} />{sourceError}</div> : source ? <><div className={`source-integrity ${verified ? "verified" : "failed"}`}><ShieldCheck size={15} />{verified ? "源码事实已与 Agent 证据核对" : "源码与 Agent 证据不一致"}<code>{source.contentSha256.slice(0, 12)}…</code></div><pre className="code-block"><code>{source.content.split("\n").map((line, index) => { const lineNumber = source.startLine + index; return <span className={lineNumber >= evidenceStart && lineNumber <= (evidenceEnd ?? evidenceStart) ? "evidence-line" : ""} key={lineNumber}><i>{lineNumber}</i>{line || " "}</span>; })}</code></pre><div className="source-actions"><button onClick={() => { setFullFile(false); setContextLines(contextLines === 5 ? 20 : 5); }}><Code2 size={14} />{contextLines === 5 ? "展开上下文" : "收起上下文"}</button><button onClick={() => setFullFile((value) => !value)}><ArrowUpRight size={14} />{fullFile ? "返回片段" : "受控全文"}</button></div></> : null}<div className="supports"><ShieldCheck size={17} /><div><span>Agent 结论</span><p>{evidence.claim}</p></div></div></> : <div className="evidence-empty"><Code2 size={28} /><p>点击结论旁的“查看证据”，核对后端返回的源码、符号、行号和 revision。</p></div>}</aside>;
}

function Composer({ open, initialPrompt, onClose, onSubmit, busy }: { open: boolean; initialPrompt: string; onClose: () => void; onSubmit: (mode: TaskMode, prompt: string) => void; busy: boolean }) {
  const [mode, setMode] = useState<TaskMode>("ANALYZE");
  const [prompt, setPrompt] = useState(initialPrompt);
  useEffect(() => { if (open) setPrompt(initialPrompt); }, [open, initialPrompt]);
  if (!open) return null;
  return <div className="modal-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}><div className="composer-modal" role="dialog" aria-modal="true" aria-label="新建任务"><div className="composer-title"><div><span>新建真实任务</span><h2>你希望 LegacyPilot 做什么？</h2></div><button className="icon-button" onClick={onClose} aria-label="关闭新建任务"><X size={19} /></button></div><div className="mode-options">{(Object.keys(modeMeta) as TaskMode[]).map((item) => <button key={item} className={`mode-option option-${item.toLowerCase()} ${mode === item ? "active" : ""}`} onClick={() => setMode(item)} aria-pressed={mode === item}><span className="mode-option-icon">{item === "EXPLAIN" ? <MessageSquareText size={19} /> : item === "ANALYZE" ? <Search size={19} /> : <Code2 size={19} />}</span><strong>{modeMeta[item].label}</strong><small>{modeMeta[item].description}</small>{mode === item && <CheckCircle2 size={17} className="selected-check" />}</button>)}</div><label className="prompt-input"><span>任务描述</span><textarea value={prompt} onChange={(event) => setPrompt(event.target.value)} rows={5} autoFocus /></label><div className="composer-hint"><ShieldCheck size={15} /><span>{mode === "CHANGE" ? "任务会真实创建工作区并执行；高风险步骤仍需独立审批。" : "任务强制只读，结构化结论和证据由真实后端生成。"}</span></div><div className="composer-actions"><button className="secondary" onClick={onClose}>取消</button><button className="primary start-button" disabled={busy || !prompt.trim()} onClick={() => onSubmit(mode, prompt.trim())}><Play size={15} />{busy ? "正在创建…" : "创建并运行"}</button></div></div></div>;
}

function ConnectionModal({ open, value, error, onClose, onSave }: { open: boolean; value: ConnectionSettings; error?: string; onClose: () => void; onSave: (value: ConnectionSettings) => void }) {
  const [draft, setDraft] = useState(value);
  useEffect(() => { if (open) setDraft(value); }, [open, value]);
  if (!open) return null;
  return <div className="modal-backdrop"><div className="composer-modal connection-modal" role="dialog" aria-modal="true" aria-label="连接真实后端"><div className="composer-title"><div><span>真实 API</span><h2>连接 LegacyPilot 后端</h2></div><button className="icon-button" onClick={onClose} aria-label="关闭连接设置"><X size={19} /></button></div><p className="connection-copy">开发环境留空地址即可使用 Vite 代理。JWT 只保存在当前浏览器标签页，不会写入长期存储。</p>{error && <div className="connection-error" role="alert"><AlertTriangle size={16} />{error}</div>}<label className="prompt-input"><span>API 地址</span><input value={draft.apiBaseUrl} placeholder="留空，或填写 https://legacypilot.example" onChange={(event) => setDraft({ ...draft, apiBaseUrl: event.target.value.trim() })} /></label><label className="prompt-input"><span>开发者 JWT</span><input type="password" value={draft.developerToken} placeholder="用于读取、创建和运行任务" onChange={(event) => setDraft({ ...draft, developerToken: event.target.value.trim() })} /></label><label className="prompt-input"><span>独立审批人 JWT</span><input type="password" value={draft.approverToken} placeholder="仅审批时使用，必须是另一身份" onChange={(event) => setDraft({ ...draft, approverToken: event.target.value.trim() })} /></label><div className="composer-actions"><button className="secondary" onClick={onClose}>取消</button><button className="primary" onClick={() => onSave(draft)}>保存并连接</button></div></div></div>;
}

const capabilityLabels: Record<string, string> = {
  SOURCE_BROWSING: "固定快照源码查看",
  TEXT_RETRIEVAL: "通用文本检索",
  JAVA_SEMANTIC_ANALYSIS: "Java 语义分析",
  MAVEN_VERIFICATION: "Maven 变更验证",
};

const warningLabels: Record<string, string> = {
  UNCOMMITTED_CHANGES_EXCLUDED: "检测到未提交改动；项目仍可接入，但分析只包含固定 commit 中的内容。",
  JAVA_SEMANTIC_ANALYSIS_UNAVAILABLE: "未检测到 Java 源码；仍可进行源码查看和通用文本分析。",
  MAVEN_VERIFICATION_UNAVAILABLE: "未检测到 Maven 构建；导入和只读分析不受影响，CHANGE 暂无 Maven 验证。",
  GRADLE_DETECTED_BUT_VERIFICATION_UNAVAILABLE: "检测到 Gradle；当前版本尚未提供 Gradle 自动验证。",
  TECHNOLOGY_SCAN_TRUNCATED: "仓库文件较多，技术栈扫描结果可能不完整。",
  DIRECTORY_SNAPSHOT_CREATED: "这是普通本地目录；LegacyPilot 已创建内部只读快照，原目录不会被修改。",
  GIT_HISTORY_UNAVAILABLE: "原项目没有 Git 历史，因此不能选择 Branch、Tag 或历史 Commit。",
};

function ProjectOnboardingModal({ open, onClose, onInspect, onInspectDirectory, onRegister }: {
  open: boolean;
  onClose: () => void;
  onInspect: (source: string, revision: string) => Promise<ProjectPreflight>;
  onInspectDirectory: (directoryName: string, files: File[]) => Promise<ProjectPreflight>;
  onRegister: (source: string, revision: string) => Promise<void>;
}) {
  const directoryInput = useRef<HTMLInputElement>(null);
  const [source, setSource] = useState("");
  const [directoryName, setDirectoryName] = useState("");
  const [directoryFiles, setDirectoryFiles] = useState<File[]>([]);
  const [revision, setRevision] = useState("HEAD");
  const [inspection, setInspection] = useState<ProjectPreflight>();
  const [problem, setProblem] = useState<string>();
  const [busy, setBusy] = useState(false);
  useEffect(() => { if (open) { setSource(""); setDirectoryName(""); setDirectoryFiles([]); setRevision("HEAD"); setInspection(undefined); setProblem(undefined); } }, [open]);
  if (!open) return null;
  const inspect = async () => {
    setBusy(true); setProblem(undefined);
    try { setInspection(directoryFiles.length > 0 ? await onInspectDirectory(directoryName, directoryFiles) : await onInspect(source.trim(), revision.trim())); }
    catch (cause) { setInspection(undefined); setProblem((cause as Error).message); }
    finally { setBusy(false); }
  };
  const register = async () => {
    setBusy(true); setProblem(undefined);
    try { if (inspection) await onRegister(inspection.source, inspection.resolvedRevision); }
    catch (cause) { setProblem((cause as Error).message); }
    finally { setBusy(false); }
  };
  const updateSource = (value: string) => { setSource(value); setDirectoryName(""); setDirectoryFiles([]); setInspection(undefined); setProblem(undefined); };
  const updateRevision = (value: string) => { setRevision(value); setInspection(undefined); setProblem(undefined); };
  const selectDirectory = (files: FileList | null) => {
    const selected = Array.from(files ?? []);
    if (selected.length === 0) return;
    const rootName = selected[0]?.webkitRelativePath.split("/")[0] || "local-project";
    const accepted = selected.filter((file) => !file.webkitRelativePath.split("/").some((part) => DIRECTORY_UPLOAD_IGNORED.has(part)));
    const oversized = accepted.find((file) => file.size > MAX_DIRECTORY_FILE_BYTES);
    const totalBytes = accepted.reduce((total, file) => total + file.size, 0);
    setSource(""); setDirectoryName(rootName); setRevision("HEAD"); setInspection(undefined);
    if (oversized) {
      setDirectoryFiles([]);
      setProblem(`文件 ${oversized.webkitRelativePath || oversized.name} 超过 64 MB。请排除大型产物，或填写后端可访问的本地目录路径。`);
      return;
    }
    if (totalBytes > MAX_DIRECTORY_TOTAL_BYTES) {
      setDirectoryFiles([]);
      setProblem(`项目有效文件总计 ${(totalBytes / 1024 / 1024).toFixed(1)} MB，超过 256 MB 接入上限。请精简依赖和构建产物，或填写后端可访问的本地目录路径。`);
      return;
    }
    if (accepted.length === 0) {
      setDirectoryFiles([]); setProblem("所选目录中没有可接入的项目文件。");
      return;
    }
    setDirectoryFiles(accepted); setProblem(undefined);
  };
  const sourceKindLabel = inspection?.sourceKind === "LOCAL_DIRECTORY" ? "本地目录快照" : inspection?.sourceKind === "LOCAL_GIT" ? "本地 Git" : "公共远程 Git";
  return <div className="modal-backdrop" onMouseDown={(event) => event.target === event.currentTarget && !busy && onClose()}><div className="composer-modal onboarding-modal" role="dialog" aria-modal="true" aria-label="接入项目"><div className="composer-title"><div><span>项目接入</span><h2>打开本地项目或 Git 仓库</h2></div><button className="icon-button" onClick={onClose} disabled={busy} aria-label="关闭项目接入"><X size={19} /></button></div><p className="connection-copy">项目不需要使用 Git，也不需要是 Java 项目或能够编译。选择本机目录会上传文件并创建内部只读快照，不会修改原目录。</p><div className="directory-picker"><input ref={directoryInput} className="visually-hidden" type="file" multiple {...({ webkitdirectory: "" } as React.InputHTMLAttributes<HTMLInputElement>)} onChange={(event) => selectDirectory(event.target.files)} /><button type="button" className="secondary" onClick={() => directoryInput.current?.click()}><FolderOpen size={16} />选择本地目录</button><span>{directoryFiles.length > 0 ? `已选择 ${directoryName}（${directoryFiles.length} 个文件）` : "或者填写后端可访问的路径 / Git URL"}</span></div><div className="onboarding-fields"><label className="prompt-input"><span>本地目录、Git 仓库路径或公共 Git URL</span><input value={source} autoFocus={!directoryFiles.length} disabled={directoryFiles.length > 0} placeholder="/absolute/path/to/project" onChange={(event) => updateSource(event.target.value)} /></label><label className="prompt-input"><span>Branch、Tag 或 Commit（仅 Git）</span><input value={revision} disabled={directoryFiles.length > 0} placeholder="HEAD" onChange={(event) => updateRevision(event.target.value)} /></label></div>{problem && <div className="connection-error" role="alert"><AlertTriangle size={16} />{problem}</div>}{inspection && <div className="preflight-result"><div className="preflight-heading"><CheckCircle2 size={18} /><div><strong>可以接入</strong><span>已固定到 {inspection.resolvedRevision.slice(0, 12)}… · 未执行编译</span></div></div><dl><div><dt>内部快照</dt><dd title={inspection.repositoryPath}>{inspection.repositoryPath}</dd></div><div><dt>类型</dt><dd>{sourceKindLabel}</dd></div><div><dt>技术栈</dt><dd>{inspection.technologies.join(" · ")}</dd></div><div><dt>来源状态</dt><dd>{inspection.sourceKind === "LOCAL_DIRECTORY" ? "已生成不可变快照" : inspection.clean ? "干净" : "有未提交改动（不纳入固定 revision）"}</dd></div></dl><div className="capability-list"><span>可用能力</span>{inspection.capabilities.map((item) => <strong key={item}><Check size={13} />{capabilityLabels[item] ?? item}</strong>)}</div>{inspection.warnings.length > 0 && <div className="preflight-warnings">{inspection.warnings.map((item) => <p key={item}><AlertTriangle size={13} />{warningLabels[item] ?? item}</p>)}</div>}</div>}<div className="composer-actions"><button className="secondary" onClick={onClose} disabled={busy}>取消</button>{inspection ? <><button className="secondary" onClick={() => setInspection(undefined)} disabled={busy}>重新检查</button><button className="primary" onClick={() => void register()} disabled={busy}>{busy ? "接入中…" : "确认接入并切换"}</button></> : <button className="primary" onClick={() => void inspect()} disabled={busy || (!source.trim() && directoryFiles.length === 0)}>{busy ? "正在预检…" : "预检项目"}</button>}</div></div></div>;
}

function EmptyWorkspace({ loading, error, hasProjects, onSettings, onRefresh, onOnboard }: { loading: boolean; error?: string; hasProjects: boolean; onSettings: () => void; onRefresh: () => void; onOnboard: () => void }) {
  return <div className="empty-workspace"><div className="agent-orbit">{loading ? <LoaderCircle className="spin" size={22} /> : error ? <AlertTriangle size={22} /> : <Bot size={22} />}<span /></div><h1>{loading ? "正在连接真实后端" : error ? "后端连接失败" : hasProjects ? "这个项目还没有任务" : "后端中还没有项目"}</h1><p>{error ?? (hasProjects ? "点击“新建任务”，LegacyPilot 会创建真实任务和运行。" : "接入任意本地目录或 Git 项目；不要求 Java，也不会预先执行编译。")}</p><div><button className="secondary" onClick={onSettings}><Settings size={15} />连接设置</button>{!error && <button className="secondary" onClick={onOnboard}><FolderPlus size={15} />接入项目</button>}<button className="primary" onClick={onRefresh}><RefreshCw size={15} />重新读取</button></div></div>;
}

function App() {
  const [settings, setSettings] = useState(defaultSettings);
  const [projects, setProjects] = useState<ApiProject[]>([]);
  const [projectId, setProjectId] = useState(() => sessionStorage.getItem(ACTIVE_PROJECT_KEY) ?? "");
  const [tasks, setTasks] = useState<DemoTask[]>([]);
  const [selectedId, setSelectedId] = useState<string>();
  const [selectedEvidence, setSelectedEvidence] = useState<string>();
  const [composerOpen, setComposerOpen] = useState(false);
  const [composerPrompt, setComposerPrompt] = useState("分析每日累计转账限额应该怎么实现，会影响哪些组件？");
  const [conversationMode, setConversationMode] = useState<TaskMode>("ANALYZE");
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [onboardingOpen, setOnboardingOpen] = useState(false);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [approvalBusy, setApprovalBusy] = useState(false);
  const [recoveryBusy, setRecoveryBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [runEvents, setRunEvents] = useState<Record<string, ApiRunEvent[]>>({});
  const [liveRunId, setLiveRunId] = useState<string>();
  const renderedConversation = useRef<{ id?: string; turns: number }>({ turns: 0 });
  const api = useMemo(() => new LegacyPilotApi(settings), [settings]);
  const project = projects.find((item) => item.id === projectId);
  const selected = tasks.find((task) => task.id === selectedId) ?? tasks[0];
  const conversationTasks = useMemo(() => selected ? tasks.filter((task) => task.conversationId === selected.conversationId).slice().reverse() : [], [tasks, selected?.conversationId]);
  const conversationRoot = conversationTasks[0] ?? selected;
  const conversationHeads = useMemo(() => {
    const groups = new Map<string, DemoTask[]>();
    for (const task of tasks) groups.set(task.conversationId, [...(groups.get(task.conversationId) ?? []), task]);
    return [...groups.values()].map((items) => {
      const latest = items[0];
      const root = items[items.length - 1];
      return { ...latest, title: root.title, prompt: root.prompt };
    });
  }, [tasks]);
  const currentEvidence = useMemo(() => selected?.evidence.find((item) => item.id === selectedEvidence), [selected, selectedEvidence]);

  const load = useCallback(async (preferredProjectId?: string) => {
    setLoading(true);
    try {
      if (!settings.developerToken) {
        try {
          const demo = await api.getDemoSession();
          const next = {
            ...settings,
            developerToken: demo.developer.token,
            approverToken: demo.approver.token,
            demoSession: true,
          };
          sessionStorage.setItem(SETTINGS_KEY, JSON.stringify(next));
          sessionStorage.setItem(ACTIVE_PROJECT_KEY, demo.project.id);
          setSettings(next);
          setProjectId(demo.project.id);
          setError(undefined);
          return;
        } catch { /* continue with regular authentication feedback */ }
      }
      const available = await api.listProjects();
      setProjects(available);
      const activeId = preferredProjectId && available.some((item) => item.id === preferredProjectId) ? preferredProjectId : available[0]?.id ?? "";
      setProjectId(activeId);
      if (activeId) sessionStorage.setItem(ACTIVE_PROJECT_KEY, activeId);
      const summaries = activeId ? await api.listTasks(activeId) : [];
      const hydrated = await Promise.all(summaries.map((item) => hydrateTask(api, item)));
      setTasks(hydrated);
      setSelectedId((current) => hydrated.some((item) => item.id === current) ? current : hydrated[0]?.id);
      setError(undefined);
    } catch (cause) {
      const problem = cause as ApiError;
      if (problem.status === 401 && settings.demoSession) {
        try {
          const demo = await api.getDemoSession();
          const next = {
            ...settings,
            developerToken: demo.developer.token,
            approverToken: demo.approver.token,
            demoSession: true,
          };
          sessionStorage.setItem(SETTINGS_KEY, JSON.stringify(next));
          setSettings(next);
          setError(undefined);
          return;
        } catch { /* the demo server is no longer available */ }
      }
      setError(`${problem.message}${problem.correlationId ? `（关联 ID：${problem.correlationId}）` : ""}`);
    } finally { setLoading(false); }
  }, [api, settings]);

  useEffect(() => { void load(projectId); }, [api]);
  useEffect(() => {
    const runId = selected?.runId;
    if (!runId) return;
    setLiveRunId(undefined);
    return api.watchRunEvents(
      runId,
      (event) => {
        setRunEvents((current) => {
          const values = current[runId] ?? [];
          if (values.some((value) => value.sequence === event.sequence)) return current;
          return { ...current, [runId]: [...values, event].sort((left, right) => left.sequence - right.sequence).slice(-100) };
        });
        void load(projectId);
      },
      (connected) => setLiveRunId(connected ? runId : undefined),
    );
  }, [api, selected?.runId, load, projectId]);
  useEffect(() => {
    if (error || !tasks.some((task) => task.status === "RUNNING" || task.rawStatus === "WAITING_FOR_APPROVAL")) return;
    const timer = window.setInterval(() => void load(projectId), liveRunId ? 10_000 : 2500);
    return () => window.clearInterval(timer);
  }, [tasks, load, projectId, error, liveRunId]);
  useEffect(() => {
    const previous = renderedConversation.current;
    if (previous.id === selected?.conversationId && conversationTasks.length > previous.turns) {
      window.requestAnimationFrame(() => {
        const scroller = document.querySelector<HTMLElement>(".main-scroll");
        if (scroller) scroller.scrollTo({ top: scroller.scrollHeight, behavior: "smooth" });
      });
    }
    renderedConversation.current = { id: selected?.conversationId, turns: conversationTasks.length };
  }, [selected?.conversationId, conversationTasks.length]);

  const chooseProject = (id: string) => { if (id === "__remove_current_project__") { void removeCurrentProject(); return; } sessionStorage.setItem(ACTIVE_PROJECT_KEY, id); setSelectedEvidence(undefined); void load(id); };
  const openComposer = (prompt = "分析每日累计转账限额应该怎么实现，会影响哪些组件？") => { setComposerPrompt(prompt); setComposerOpen(true); };
  const submit = async (mode: TaskMode, prompt: string, conversationId?: string) => {
    if (!project) return;
    setSubmitting(true);
    try {
      const created = await api.createTask(project.id, mode, prompt, conversationId);
      const run = await api.startRun(created.id);
      setComposerOpen(false);
      await load(project.id);
      setSelectedId(created.id);
      void api.executeRun(run.id, run.version).then(() => load(project.id)).catch((cause: Error) => setError(cause.message));
    } catch (cause) { setError((cause as Error).message); } finally { setSubmitting(false); }
  };
  const focusConversationComposer = () => {
    window.requestAnimationFrame(() => document.querySelector<HTMLTextAreaElement>(".conversation-input textarea")?.focus());
  };
  const decide = async (approved: boolean, reason: string) => {
    if (!selected?.runId || !selected.approval) return;
    setApprovalBusy(true);
    try {
      const decision = await api.approve(selected.runId, selected.runVersion ?? 0, selected.approval, approved, reason);
      void api.resumeRun(selected.runId, decision.runVersion, selected.approval.actionDigest).then(() => load(projectId)).catch((cause: Error) => setError(cause.message));
      await load(projectId);
    } catch (cause) {
      const problem = cause as ApiError;
      const hint = problem.status === 401 ? "审批人凭据已过期，请在连接设置中更新。" : problem.status === 403 ? "当前审批人身份没有 APPROVER 权限，且开发者不能自批。" : problem.status === 409 ? "计划或动作已变化，请刷新后重新审核。" : problem.message;
      setError(`${hint}${problem.correlationId ? `（关联 ID：${problem.correlationId}）` : ""}`);
    } finally { setApprovalBusy(false); }
  };
  const applyToSource = async (targetPath: string) => {
    if (!selected?.runId) throw new Error("当前任务没有可应用的运行结果");
    const result = await api.applyChangeToSource(selected.runId, targetPath);
    return result.status === "ALREADY_APPLIED"
      ? `目标项目已经包含这次变更（${result.alreadyAppliedFiles} 个文件），无需重复写入。`
      : `已将 ${result.appliedFiles.length} 个文件应用到 ${result.targetPath}，IDEA 将显示对应变更。`;
  };
  const recover = async (action: "CANCEL" | "RETRY_CURRENT_STEP" | "REPLAN") => {
    if (!selected?.runId || selected.runVersion === undefined) return;
    setRecoveryBusy(true);
    try {
      const apply = async (version: number) => {
        if (action === "CANCEL") await api.cancelRun(selected.runId!, version, "由 Web 工作台取消");
        else if (action === "RETRY_CURRENT_STEP") await api.retryRun(selected.runId!, version);
        else await api.replanRun(selected.runId!, version);
      };
      const current = await api.getRun(selected.runId);
      try {
        await apply(current.version);
      } catch (cause) {
        const problem = cause as ApiError;
        if (problem.status !== 409) throw cause;
        const refreshed = await api.getRun(selected.runId);
        await apply(refreshed.version);
      }
      await load(projectId);
    } catch (cause) {
      const problem = cause as ApiError;
      const hint = problem.status === 409 ? "运行状态已被另一页面更新，请刷新后重试。" : problem.message;
      setError(`${hint}${problem.correlationId ? `（关联 ID：${problem.correlationId}）` : ""}`);
    } finally { setRecoveryBusy(false); }
  };
  const saveSettings = (next: ConnectionSettings) => {
    const manual = { ...next, demoSession: false };
    sessionStorage.setItem(SETTINGS_KEY, JSON.stringify(manual));
    setSettings(manual);
    setSettingsOpen(false);
  };

  const inspectProject = (source: string, revision: string) => api.preflightProject(source, revision);
  const inspectDirectory = (directoryName: string, files: File[]) => api.preflightDirectory(directoryName, files);
  const registerProject = async (source: string, revision: string) => {
    const created = await api.registerProject(source, revision);
    sessionStorage.setItem(ACTIVE_PROJECT_KEY, created.id);
    setProjectId(created.id);
    setSelectedEvidence(undefined);
    setOnboardingOpen(false);
    await load(created.id);
  };

  const removeCurrentProject = async () => {
    if (!project || !window.confirm(`确认从工作台移除“${projectName(project)}”？\n\n历史任务与审计数据会保留，但该项目将不再出现在项目列表中。`)) return;
    setSubmitting(true);
    try {
      await api.removeProject(project.id);
      sessionStorage.removeItem(ACTIVE_PROJECT_KEY);
      setSelectedId(undefined); setSelectedEvidence(undefined); setTasks([]);
      await load();
    } catch (cause) {
      const problem = cause as ApiError;
      setError(`${problem.message}${problem.correlationId ? `（关联 ID：${problem.correlationId}）` : ""}`);
    } finally { setSubmitting(false); }
  };

  return <div className="app-shell"><Sidebar projects={projects} project={project} tasks={conversationHeads} selectedId={selected?.conversationId} onProject={chooseProject} onSelect={(id) => { setSelectedId(id); setSelectedEvidence(undefined); }} onNew={() => openComposer()} onOnboard={() => setOnboardingOpen(true)} onSettings={() => setSettingsOpen(true)} open={sidebarOpen} onClose={() => setSidebarOpen(false)} /><div className="workspace"><Topbar task={conversationRoot} project={project} connected={!error && !loading} onMenu={() => setSidebarOpen(true)} onRefresh={() => void load(projectId)} onSettings={() => setSettingsOpen(true)} />{selected && conversationRoot ? <><main className="main-scroll"><div className="main-column conversation-column"><TaskHeader task={selected} title={conversationRoot.prompt} turns={conversationTasks.length} /><div className="conversation-thread">{conversationTasks.map((turn) => <article className="conversation-turn" key={turn.id}><div className="conversation-user"><div className="user-avatar">你</div><div><span><ModeBadge mode={turn.mode} />{turn.time}</span><p>{turn.prompt}</p></div></div><div className="conversation-assistant"><div className="assistant-avatar"><Bot size={15} /></div><div><ResultView task={turn} onEvidence={(evidenceId) => { setSelectedId(turn.id); setSelectedEvidence(evidenceId); }} onFollowUp={focusConversationComposer} />{turn.id === selected.id && <ExecutionDetails task={turn} events={turn.runId ? runEvents[turn.runId] ?? [] : []} live={liveRunId === turn.runId} recoveryBusy={recoveryBusy} approvalBusy={approvalBusy} onRecover={recover} onDecision={decide} onApply={applyToSource} defaultTarget={project?.source.startsWith("/") ? project.source : ""} />}</div></div></article>)}</div></div></main><ConversationComposer mode={conversationMode} onMode={setConversationMode} busy={submitting} disabled={selected.status === "RUNNING" || selected.rawStatus === "WAITING_FOR_APPROVAL"} onSubmit={(prompt) => void submit(conversationMode, prompt, selected.conversationId)} /></> : <EmptyWorkspace loading={loading} error={error} hasProjects={projects.length > 0} onSettings={() => setSettingsOpen(true)} onRefresh={() => void load(projectId)} onOnboard={() => setOnboardingOpen(true)} />}{!currentEvidence && selected?.evidence.length > 0 && <button className="evidence-peek" onClick={() => setSelectedEvidence(selected.evidence[0]?.id)}><PanelRightOpen size={17} />源码证据<span>{selected.evidence.length}</span></button>}</div><EvidencePanel evidence={currentEvidence} projectId={selected?.projectId} api={api} onClose={() => setSelectedEvidence(undefined)} />{sidebarOpen && <button className="sidebar-scrim" onClick={() => setSidebarOpen(false)} aria-label="关闭侧栏" />}<Composer open={composerOpen} initialPrompt={composerPrompt} onClose={() => setComposerOpen(false)} onSubmit={(mode, prompt) => submit(mode, prompt)} busy={submitting} /><ProjectOnboardingModal open={onboardingOpen} onClose={() => setOnboardingOpen(false)} onInspect={inspectProject} onInspectDirectory={inspectDirectory} onRegister={registerProject} /><ConnectionModal open={settingsOpen} value={settings} error={error} onClose={() => setSettingsOpen(false)} onSave={saveSettings} /></div>;
}

createRoot(document.getElementById("root")!).render(<React.StrictMode><App /></React.StrictMode>);

export type TaskMode = "EXPLAIN" | "ANALYZE" | "CHANGE";
export type TaskStatus = "RUNNING" | "SUCCEEDED" | "WAITING" | "FAILED";
export type StepState = "DONE" | "RUNNING" | "PENDING";

export interface RunStep {
  label: string;
  detail: string;
  tool: string;
  state: StepState;
}

export interface Evidence {
  id: string;
  path: string;
  symbol: string;
  lines: string;
  excerpt: string;
  claim: string;
  revision: string;
  excerptSha256?: string;
}

export interface ResultSection {
  title: string;
  items: Array<{ text: string; evidenceId?: string }>;
}

export interface ApprovalContext {
  actionDigest: string;
  planDigest: string;
  tool: string;
  reason: string;
  input: unknown;
}

export interface ChangeReview {
  runId: string;
  status: string;
  planVersion: number;
  steps: string[];
  affectedFiles: string[];
  risk: string;
  rationale: string;
  action?: ApprovalContext & { summary: string };
  policyChecks: Array<{ name: string; status: string; detail: string }>;
  diff: Array<{
    path: string;
    status: string;
    additions: number;
    deletions: number;
    truncated: boolean;
    error: string;
    lines: Array<{ oldLine?: number; newLine?: number; kind: "CONTEXT" | "ADD" | "DELETE"; text: string }>;
  }>;
  verification: Array<{
    category: "COMPILE" | "TEST" | "STATIC_ANALYSIS" | "COVERAGE";
    name: string;
    status: string;
    required: string;
    summary: string;
    command: string;
    duration: string;
  }>;
  decision?: { decision: "APPROVED" | "DENIED"; actor: string; reason: string; actionDigest: string; planDigest: string; decidedAt: string };
  replanAvailable: boolean;
}

export interface ChangeApplicationResult {
  runId: string;
  targetPath: string;
  appliedFiles: string[];
  alreadyAppliedFiles: number;
  status: "APPLIED" | "ALREADY_APPLIED";
}

export interface RecoveryView {
  runId: string;
  status: string;
  expectedVersion: number;
  failureCategory: "NONE" | "USER_CANCELLED" | "MODEL_FAILURE" | "TOOL_FAILURE" | "POLICY_BLOCK" | "VERIFICATION_FAILURE" | "INFRASTRUCTURE_FAILURE";
  replaySafety: "SAFE_FROM_CHECKPOINT" | "HUMAN_REVIEW_REQUIRED";
  diagnosis: string;
  allowedActions: Array<"VIEW_DIAGNOSTICS" | "WAIT_FOR_APPROVAL" | "HUMAN_REVIEW" | "CANCEL" | "RETRY_CURRENT_STEP" | "REPLAN">;
  attempts: Array<{
    number: number;
    commandId: string;
    operation: string;
    status: string;
    workerClaims: number;
    acceptedRunVersion: number;
    actor: string;
    correlationId: string;
    error: string;
    requestedAt: string;
    updatedAt: string;
  }>;
  checkpointUpdatedAt?: string;
}

export interface DemoTask {
  id: string;
  conversationId: string;
  projectId?: string;
  runId?: string;
  title: string;
  prompt: string;
  mode: TaskMode;
  status: TaskStatus;
  rawStatus?: string;
  runVersion?: number;
  time: string;
  steps: RunStep[];
  summary: string;
  sections: ResultSection[];
  evidence: Evidence[];
  unknowns: string[];
  approval?: ApprovalContext;
  changeReview?: ChangeReview;
  recovery?: RecoveryView;
  metrics: {
    evidence: number;
    statements: number;
    unsupported: number;
    writes: number;
    tokens: number;
    duration: string;
  };
}

export interface ApiProject {
  id: string;
  source: string;
  repositoryPath: string;
  baseRevision: string;
  registeredAt: string;
}

export interface ProjectPreflight {
  source: string;
  repositoryPath: string;
  resolvedRevision: string;
  sourceKind: "LOCAL_GIT" | "LOCAL_DIRECTORY" | "PUBLIC_HTTP";
  clean: boolean;
  compilation: "NOT_RUN";
  technologies: string[];
  capabilities: string[];
  warnings: string[];
}

export interface ApiRun {
  id: string;
  taskId: string;
  status: string;
  version: number;
  workspaceId?: string;
  terminalReason?: string;
  createdAt: string;
  updatedAt: string;
  history: Array<{ sequence: number; from: string; to: string; occurredAt: string; reason: string }>;
}

export interface ApiRunEvent {
  sequence: number;
  time: string;
  phase: string;
  status: string;
  summary: string;
  correlationId: string;
  type: string;
}

export interface ApiSourceSnippet {
  projectId: string;
  revision: string;
  path: string;
  startLine: number;
  endLine: number;
  totalLines: number;
  content: string;
  contentSha256: string;
  requestedRangeSha256: string;
  snippetSha256: string;
  truncated: boolean;
}

export interface ApiTaskSummary {
  id: string;
  projectId: string;
  conversationId: string;
  mode: TaskMode;
  requirement: string;
  criteria: string[];
  createdAt: string;
  latestRun?: ApiRun;
}

export interface ApiCheckpoint {
  runId: string;
  status: string;
  plan?: { version: number; steps: string[]; affectedFiles: string[]; risk: string; rationale: string };
  steps: number;
  retries: number;
  usage: { inputTokens: number; outputTokens: number; estimatedCostUsd: number; model: string };
  startedAt: string;
  updatedAt: string;
  pendingAction?: { type: string; tool: string; input: unknown; reason: string };
  observation: string;
}

export interface ApiArtifact {
  schemaVersion: number;
  taskMode: string;
  summary: string;
  sections: Record<string, string[]>;
  evidence: Array<{ path: string; symbol: string; startLine: number; endLine: number; excerpt: string; claim: string; revision: string; excerptSha256?: string }>;
  unknowns: string[];
}

export interface ApiReport {
  runId: string;
  status: string;
  summary: string;
  steps: number;
  modelTokens: number;
  estimatedCostUsd: number;
  duration: string | number;
  risk: string;
  plan: string[];
  verification: Array<Record<string, string>>;
  taskMode: string;
  artifact?: ApiArtifact;
  groundingMetrics: Record<string, string>;
}

export interface ConnectionSettings {
  apiBaseUrl: string;
  developerToken: string;
  approverToken: string;
  demoSession?: boolean;
}

export interface DemoSession {
  mode: "demo";
  developer: { subject: string; token: string; roles: string[] };
  approver: { subject: string; token: string; roles: string[] };
  project: { id: string; source: string; revision: string };
  issuedAt: string;
}

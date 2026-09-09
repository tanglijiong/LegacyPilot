import type {
  ApiArtifact,
  ApiCheckpoint,
  ApiProject,
  ApiReport,
  ApiRun,
  ApiRunEvent,
  ApiSourceSnippet,
  ApiTaskSummary,
  ApprovalContext,
  ConnectionSettings,
  ChangeReview,
  ChangeApplicationResult,
  RecoveryView,
  DemoSession,
  ProjectPreflight,
  TaskMode,
} from "./types";

export class ApiError extends Error {
  constructor(message: string, readonly status: number, readonly code?: string, readonly correlationId?: string) {
    super(message);
  }
}

export class LegacyPilotApi {
  constructor(private readonly settings: ConnectionSettings) {}

  getDemoSession() { return this.request<DemoSession>("/api/v1/demo/session", {}, false, true); }
  listProjects() { return this.request<ApiProject[]>("/api/v1/projects"); }
  removeProject(projectId: string) {
    return this.request<void>(`/api/v1/projects/${encodeURIComponent(projectId)}`, { method: "DELETE" });
  }
  preflightProject(source: string, revision: string) {
    return this.request<ProjectPreflight>("/api/v1/projects/preflight", {
      method: "POST",
      body: JSON.stringify({ source, revision: revision || null }),
    });
  }
  preflightDirectory(directoryName: string, files: File[]) {
    const body = new FormData();
    body.append("directoryName", directoryName);
    for (const file of files) {
      const parts = file.webkitRelativePath.split("/").filter(Boolean);
      const relativePath = parts.length > 1 ? parts.slice(1).join("/") : file.name;
      body.append("paths", relativePath);
      body.append("files", file, file.name);
    }
    return this.request<ProjectPreflight>("/api/v1/projects/preflight-directory", {
      method: "POST",
      body,
    });
  }
  registerProject(source: string, revision: string) {
    return this.request<ApiProject>("/api/v1/projects", {
      method: "POST",
      body: JSON.stringify({ source, revision: revision || null }),
    });
  }
  listTasks(projectId: string) { return this.request<ApiTaskSummary[]>(`/api/v1/projects/${encodeURIComponent(projectId)}/tasks`); }
  createTask(projectId: string, mode: TaskMode, requirement: string, conversationId?: string) {
    return this.request<ApiTaskSummary>(`/api/v1/projects/${encodeURIComponent(projectId)}/tasks`, {
      method: "POST",
      body: JSON.stringify({ mode, requirement, criteria: [], conversationId }),
    });
  }
  startRun(taskId: string) { return this.request<ApiRun>(`/api/v1/tasks/${encodeURIComponent(taskId)}/runs`, { method: "POST" }); }
  getRun(runId: string) { return this.request<ApiRun>(`/api/v1/runs/${encodeURIComponent(runId)}`); }
  executeRun(runId: string, version: number) { return this.runCommand(runId, "execute", version, `execute-${runId}`); }
  resumeRun(runId: string, version: number, actionDigest: string) {
    const key = `resume-${runId.slice(-32)}-${actionDigest.slice(0, 64)}`;
    return this.runCommand(runId, "resume", version, key);
  }
  retryRun(runId: string, version: number) { return this.runCommand(runId, "retry", version, `retry-${runId}-${version}`); }
  replanRun(runId: string, version: number) { return this.runCommand(runId, "replan", version, `replan-${runId}-${version}`); }
  cancelRun(runId: string, version: number, reason: string) {
    return this.request(`/api/v1/runs/${encodeURIComponent(runId)}/cancel`, {
      method: "POST",
      headers: { "If-Match": String(version) },
      body: JSON.stringify({ reason }),
    });
  }
  getCheckpoint(runId: string) { return this.request<ApiCheckpoint>(`/api/v1/runs/${encodeURIComponent(runId)}/execution`); }
  getReport(runId: string) { return this.request<ApiReport>(`/api/v1/runs/${encodeURIComponent(runId)}/report`); }
  getArtifact(runId: string) { return this.request<ApiArtifact>(`/api/v1/runs/${encodeURIComponent(runId)}/artifact`); }
  getSource(
    projectId: string,
    revision: string,
    path: string,
    startLine: number,
    endLine: number,
    contextLines = 5,
    fullFile = false,
  ) {
    const query = new URLSearchParams({
      revision,
      path,
      startLine: String(startLine),
      endLine: String(endLine),
      contextLines: String(contextLines),
      fullFile: String(fullFile),
    });
    return this.request<ApiSourceSnippet>(`/api/v1/projects/${encodeURIComponent(projectId)}/source?${query}`);
  }
  getApprovalContext(runId: string) { return this.request<ApprovalContext>(`/api/v1/runs/${encodeURIComponent(runId)}/approval-context`); }
  getChangeReview(runId: string) { return this.request<ChangeReview>(`/api/v1/runs/${encodeURIComponent(runId)}/change-review`); }
  applyChangeToSource(runId: string, targetPath: string) {
    return this.request<ChangeApplicationResult>(`/api/v1/runs/${encodeURIComponent(runId)}/apply-to-source`, {
      method: "POST",
      body: JSON.stringify({ targetPath: targetPath.trim() || null }),
    }, true);
  }
  getRecovery(runId: string) { return this.request<RecoveryView>(`/api/v1/runs/${encodeURIComponent(runId)}/recovery`); }
  watchRunEvents(
    runId: string,
    onEvent: (event: ApiRunEvent) => void,
    onConnection: (connected: boolean) => void,
  ) {
    const controller = new AbortController();
    let lastEventId = 0;
    void (async () => {
      while (!controller.signal.aborted) {
        try {
          const response = await fetch(
            `${this.settings.apiBaseUrl.replace(/\/$/, "")}/api/v1/runs/${encodeURIComponent(runId)}/events`,
            {
              headers: {
                Accept: "text/event-stream",
                ...(this.settings.developerToken ? { Authorization: `Bearer ${this.settings.developerToken}` } : {}),
                ...(lastEventId ? { "Last-Event-ID": String(lastEventId) } : {}),
              },
              signal: controller.signal,
            },
          );
          if (!response.ok || !response.body) throw new ApiError(`事件流不可用（HTTP ${response.status}）`, response.status);
          onConnection(true);
          const terminal = await consumeEventStream(response.body, (id, event) => {
            if (id <= lastEventId) return;
            lastEventId = id;
            onEvent(event);
          }, controller.signal);
          if (terminal || controller.signal.aborted) return;
          throw new Error("事件流意外关闭");
        } catch {
          if (controller.signal.aborted) return;
          onConnection(false);
          await abortableDelay(1500, controller.signal);
        }
      }
    })();
    return () => controller.abort();
  }
  approve(runId: string, version: number, context: ApprovalContext, approved: boolean, reason: string) {
    return this.request<{ runVersion: number }>(`/api/v1/runs/${encodeURIComponent(runId)}/approvals`, {
      method: "POST",
      body: JSON.stringify({
        expectedVersion: version,
        actionDigest: context.actionDigest,
        planDigest: context.planDigest,
        decision: approved ? "APPROVED" : "DENIED",
        scope: "ONCE",
        reason,
      }),
    }, true);
  }

  private runCommand(runId: string, operation: string, version: number, idempotencyKey: string) {
    return this.request(`/api/v1/runs/${encodeURIComponent(runId)}/${operation}`, {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey, "If-Match": String(version) },
    });
  }

  private async request<T = unknown>(path: string, init: RequestInit = {}, approver = false, anonymous = false): Promise<T> {
    const token = anonymous ? "" : approver ? this.settings.approverToken : this.settings.developerToken;
    const response = await fetch(`${this.settings.apiBaseUrl.replace(/\/$/, "")}${path}`, {
      ...init,
      headers: {
        Accept: "application/json",
        ...(init.body && !(init.body instanceof FormData) ? { "Content-Type": "application/json" } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...init.headers,
      },
    });
    if (!response.ok) {
      const problem = await response.json().catch(() => ({})) as { detail?: string; code?: string; correlationId?: string };
      throw new ApiError(problem.detail || `API 请求失败（HTTP ${response.status}）`, response.status, problem.code, problem.correlationId);
    }
    if (response.status === 204 || response.headers.get("content-length") === "0") return undefined as T;
    return response.json() as Promise<T>;
  }
}

const TERMINAL_EVENT_STATUSES = new Set(["SUCCEEDED", "FAILED", "CANCELLED", "DENIED", "BUDGET_EXHAUSTED"]);

async function consumeEventStream(
  stream: ReadableStream<Uint8Array>,
  onEvent: (id: number, event: ApiRunEvent) => void,
  signal: AbortSignal,
) {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let terminal = false;
  try {
    while (!signal.aborted) {
      const { done, value } = await reader.read();
      buffer += decoder.decode(value, { stream: !done });
      const frames = buffer.split(/\r?\n\r?\n/);
      buffer = frames.pop() ?? "";
      for (const frame of frames) {
        const id = Number(frame.match(/^id:\s*(\d+)\s*$/m)?.[1] ?? 0);
        const data = frame.split(/\r?\n/).filter((line) => line.startsWith("data:")).map((line) => line.slice(5).trimStart()).join("\n");
        if (!id || !data) continue;
        const event = JSON.parse(data) as ApiRunEvent;
        onEvent(id, event);
        terminal ||= TERMINAL_EVENT_STATUSES.has(event.status);
      }
      if (done || terminal) return terminal;
    }
  } finally {
    reader.releaseLock();
  }
  return terminal;
}

function abortableDelay(milliseconds: number, signal: AbortSignal) {
  return new Promise<void>((resolve) => {
    const timer = window.setTimeout(resolve, milliseconds);
    signal.addEventListener("abort", () => { window.clearTimeout(timer); resolve(); }, { once: true });
  });
}

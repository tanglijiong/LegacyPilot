import AxeBuilder from "@axe-core/playwright";
import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { fileURLToPath } from "node:url";

test.describe.configure({ mode: "serial" });

async function ready(page: Page) {
  await page.goto("/");
  await expect(page.locator(".topbar")).toContainText("API 已连接");
  await expect(page.getByRole("button", { name: "新建任务" })).toBeEnabled();
}

async function createTask(page: Page, mode: "讲解业务" | "分析需求" | "修改代码", prompt: string) {
  await page.getByRole("button", { name: "新建任务" }).click();
  const dialog = page.getByRole("dialog", { name: "新建任务" });
  await dialog.getByRole("button", { name: new RegExp(mode) }).click();
  await dialog.getByRole("textbox", { name: "任务描述" }).fill(prompt);
  await dialog.getByRole("button", { name: "创建并运行" }).click();
  await expect(page.locator(".conversation-user").last()).toContainText(prompt);
}

async function demoSession(request: APIRequestContext) {
  const response = await request.get("http://127.0.0.1:18080/api/v1/demo/session");
  expect(response.ok()).toBeTruthy();
  return response.json() as Promise<{
    developer: { token: string };
    approver: { token: string };
    project: { id: string; revision: string; source: string };
  }>;
}

async function onboardPlainProject(page: Page) {
  await page.locator(".project-add").click();
  const dialog = page.getByRole("dialog", { name: "接入项目" });
  await dialog.locator('input[type="file"]').setInputFiles(fileURLToPath(new URL("./fixtures/plain-project", import.meta.url)));
  await expect(dialog.getByText(/已选择 plain-project（2 个文件）/)).toBeVisible();
  await dialog.getByRole("button", { name: "预检项目" }).click();
  await expect(dialog.getByText("可以接入")).toBeVisible();
  await dialog.getByRole("button", { name: "确认接入并切换" }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.getByRole("combobox", { name: "选择项目" }).locator("option:checked")).toHaveText("plain-project");
}

test("从 Web 选择并接入无 Git 的本地目录，不要求编译", async ({ page }) => {
  await ready(page);
  await page.locator(".project-add").click();
  const dialog = page.getByRole("dialog", { name: "接入项目" });
  await expect(dialog.getByText(/项目不需要使用 Git/)).toBeVisible();
  await dialog.locator('input[type="file"]').setInputFiles(fileURLToPath(new URL("./fixtures/plain-project", import.meta.url)));
  await expect(dialog.getByText(/已选择 plain-project（2 个文件）/)).toBeVisible();
  await dialog.getByRole("button", { name: "预检项目" }).click();
  await expect(dialog.getByText("可以接入")).toBeVisible();
  await expect(dialog.getByText(/未执行编译/)).toBeVisible();
  await expect(dialog.getByText("本地目录快照")).toBeVisible();
  await expect(dialog.getByText("PYTHON")).toBeVisible();
  await expect(dialog.getByText(/这是普通本地目录/)).toBeVisible();
  await dialog.getByRole("button", { name: "确认接入并切换" }).click();
  await expect(dialog).not.toBeVisible();
  const projectSelector = page.getByRole("combobox", { name: "选择项目" });
  await expect(projectSelector.locator("option:checked")).toHaveText("plain-project");
  await expect(page.getByText("这个项目还没有任务")).toBeVisible();
});

test("可以从工作台移除当前项目并自动切换到剩余项目", async ({ page }) => {
  await ready(page);
  await onboardPlainProject(page);
  const projectSelector = page.getByRole("combobox", { name: "选择项目" });
  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "移除当前项目" }).click();
  await expect(projectSelector.locator("option:checked")).not.toHaveText("plain-project");
  await expect(projectSelector.locator("option", { hasText: "plain-project" })).toHaveCount(0);
});

test("EXPLAIN 主路径产生可回链源码证据，刷新后保持结果", async ({ page }) => {
  await ready(page);
  const prompt = "E2E讲解转账保存流程";
  await createTask(page, "讲解业务", prompt);
  await expect(page.getByText("转账服务创建包含账户、金额和时间的记录后交给仓储保存。")).toBeVisible();
  await page.getByRole("button", { name: /查看证据/ }).first().click();
  await expect(page.getByText("源码事实已与 Agent 证据核对")).toBeVisible();
  await page.reload();
  await expect(page.getByRole("heading", { level: 1, name: prompt })).toBeVisible();
  await expect(page.getByText("转账服务创建包含账户、金额和时间的记录后交给仓储保存。")).toBeVisible();
});

test("同一项目对话支持连续追问并在刷新后保留两轮消息", async ({ page }) => {
  await ready(page);
  const firstPrompt = "E2E连续对话：先解释转账保存流程";
  const followUpPrompt = "再说明这里最需要关注的边界条件";
  await createTask(page, "讲解业务", firstPrompt);
  await expect(page.getByText("转账服务创建包含账户、金额和时间的记录后交给仓储保存。")).toBeVisible();
  await page.getByRole("textbox", { name: "继续对话" }).fill(followUpPrompt);
  await page.getByRole("button", { name: "发送追问" }).click();
  await expect(page.getByText(followUpPrompt, { exact: true })).toBeVisible();
  await expect(page.getByText(/项目对话 · 2 轮/)).toBeVisible();
  await expect(page.getByText("转账写入集中在 TransferService，并直接调用仓储保存。")).toBeVisible();
  await page.reload();
  await expect(page.locator(".conversation-user p")).toHaveText([firstPrompt, followUpPrompt]);
  await expect(page.getByText(/项目对话 · 2 轮/)).toBeVisible();
});

test("ANALYZE 在 SSE 故障时通过真实 API 轮询完成", async ({ page }) => {
  await page.route("**/api/v1/runs/*/events", (route) => route.abort("connectionfailed"));
  await ready(page);
  const prompt = "E2E分析转账组件影响";
  await createTask(page, "分析需求", prompt);
  await expect(page.getByText("轮询降级")).toBeVisible();
  await expect(page.getByText("转账写入集中在 TransferService，并直接调用仓储保存。")).toBeVisible({ timeout: 20_000 });
});

test("SSE 真实断线后携带 Last-Event-ID 重连且事件不重不漏", async ({ page, request }) => {
  test.setTimeout(60_000);
  const eventRequestHeaders: string[] = [];
  page.on("request", (eventRequest) => {
    if (eventRequest.url().includes("/events")) {
      eventRequestHeaders.push(eventRequest.headers()["last-event-id"] ?? "");
    }
  });
  await ready(page);
  const session = await demoSession(request);
  const prompt = "[E2E_SLOW] SSE真实断线恢复";
  await createTask(page, "分析需求", prompt);
  await expect(page.locator(".run-event").first()).toBeVisible();
  const disconnected = await request.post("http://127.0.0.1:18080/api/v1/demo/faults/disconnect-event-streams", {
    headers: { Authorization: `Bearer ${session.developer.token}` },
  });
  expect(disconnected.ok()).toBeTruthy();
  expect((await disconnected.json()).disconnected).toBeGreaterThan(0);
  await expect(page.locator(".stream-state")).toHaveText("轮询降级", { timeout: 10_000 });
  await expect(page.locator(".stream-state")).toHaveText("实时", { timeout: 15_000 });
  await expect(page.getByText("转账写入集中在 TransferService，并直接调用仓储保存。")).toBeVisible({ timeout: 25_000 });
  expect(eventRequestHeaders.some((value) => Number(value) > 0)).toBeTruthy();

  const tasks = await (await request.get(
    `http://127.0.0.1:18080/api/v1/projects/${session.project.id}/tasks`,
    { headers: { Authorization: `Bearer ${session.developer.token}` } },
  )).json() as Array<{ requirement: string; latestRun: { id: string } }>;
  const runId = tasks.find((task) => task.requirement === prompt)?.latestRun.id;
  expect(runId).toBeTruthy();
  const stream = await request.get(`http://127.0.0.1:18080/api/v1/runs/${runId}/events`, {
    headers: { Authorization: `Bearer ${session.developer.token}` },
  });
  const streamText = await stream.text();
  const expectedSequences = streamText.match(/^id:\s*(\d+)$/gm)?.map((line) => Number(line.replace(/\D/g, ""))) ?? [];
  const displayedSequences = await page.locator(".run-event > code").allTextContents();
  const displayedNumbers = displayedSequences.map((value) => Number(value.replace("#", "")));
  const terminalBlock = streamText.split(/\n\s*\n/).find((block) => block.includes('"type":"run.completed"')) ?? "";
  const terminalSequence = Number(terminalBlock.match(/^id:\s*(\d+)$/m)?.[1] ?? 0);
  expect(terminalSequence).toBeGreaterThan(0);
  expect(new Set(displayedNumbers).size).toBe(displayedNumbers.length);
  expect(displayedNumbers.filter((sequence) => sequence <= terminalSequence))
    .toEqual(expectedSequences.filter((sequence) => sequence <= terminalSequence));
});

test("真实服务进程崩溃后恢复同一任务和持久化命令", async ({ page, request }) => {
  test.setTimeout(75_000);
  await ready(page);
  const prompt = "[E2E_SLOW] 服务进程重启恢复";
  await createTask(page, "分析需求", prompt);
  await expect(page.locator(".task-header .eyebrow-row")).toContainText("PLANNING");
  const restarted = await request.post("http://127.0.0.1:18081/restart", { timeout: 40_000 });
  expect(restarted.ok()).toBeTruthy();
  await page.reload();
  await expect(page.locator(".topbar")).toContainText("API 已连接", { timeout: 20_000 });
  await expect(page.getByRole("heading", { level: 1, name: prompt })).toBeVisible();
  await expect(page.getByText("转账写入集中在 TransferService，并直接调用仓储保存。")).toBeVisible({ timeout: 35_000 });

  const session = await demoSession(request);
  const tasks = await (await request.get(
    `http://127.0.0.1:18080/api/v1/projects/${session.project.id}/tasks`,
    { headers: { Authorization: `Bearer ${session.developer.token}` } },
  )).json() as Array<{ requirement: string; latestRun: { id: string } }>;
  const runId = tasks.find((task) => task.requirement === prompt)?.latestRun.id;
  expect(runId).toBeTruthy();
  const recovery = await (await request.get(`http://127.0.0.1:18080/api/v1/runs/${runId}/recovery`, {
    headers: { Authorization: `Bearer ${session.developer.token}` },
  })).json() as { attempts: Array<{ workerClaims: number }> };
  expect(recovery.attempts[0]?.workerClaims).toBeGreaterThanOrEqual(2);
});

test("CHANGE 主路径展示可信决策包并记录独立拒绝", async ({ page }) => {
  await ready(page);
  const prompt = "E2E变更审批边界";
  await createTask(page, "修改代码", prompt);
  await expect(page.getByText("CHANGE 决策工作台")).toBeVisible();
  await expect(page.getByText("等待独立审批后继续")).toBeVisible();
  await page.getByRole("textbox", { name: "审批说明 / 拒绝理由" }).fill("E2E 安全回归拒绝写入");
  await page.getByRole("button", { name: "拒绝并终止" }).click();
  await expect(page.getByText("已拒绝，受治理写操作已终止")).toBeVisible();
  await expect(page.getByText(/E2E 安全回归拒绝写入/)).toBeVisible();
});

test("安全点取消与模型失败重试均可从页面完成", async ({ page }) => {
  await ready(page);
  await createTask(page, "分析需求", "[E2E_SLOW] 取消安全点");
  await expect(page.getByRole("button", { name: "取消运行" })).toBeVisible();
  await page.getByRole("button", { name: "取消运行" }).click();
  await expect(page.getByText("用户取消")).toBeVisible();

  await createTask(page, "分析需求", "[E2E_FAIL_ONCE] 重试模型失败");
  await expect(page.locator(".recovery-panel").getByText("模型失败", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "重试当前步骤" }).click();
  await expect(page.getByText("转账写入集中在 TransferService，并直接调用仓储保存。")).toBeVisible({ timeout: 20_000 });
  await page.locator(".execution-details > summary").click();
  await page.getByText(/查看 2 次持久化 attempt/).click();
  await expect(page.getByText(/#2 · RETRY/)).toBeVisible();
});

test("真实 Maven 编译失败后重新规划、重新审批并恢复成功", async ({ page }) => {
  test.setTimeout(120_000);
  await ready(page);
  await createTask(page, "修改代码", "[E2E_VERIFY_FAIL_ONCE] Maven失败恢复");
  await expect(page.getByText("CHANGE 决策工作台")).toBeVisible();
  await page.getByRole("textbox", { name: "审批说明 / 拒绝理由" }).fill("批准注入真实编译失败");
  await page.getByRole("button", { name: "批准并继续" }).click();
  await expect(page.locator(".recovery-panel").getByText("验证失败", { exact: true })).toBeVisible({ timeout: 60_000 });
  await page.getByRole("button", { name: "重新规划" }).click();
  await expect(page.getByText("等待独立审批后继续")).toBeVisible({ timeout: 20_000 });
  await page.getByRole("textbox", { name: "审批说明 / 拒绝理由" }).fill("重新核对修复后的动作和计划摘要");
  await page.getByRole("button", { name: "批准并继续" }).click();
  await expect(page.locator(".task-header .eyebrow-row")).toContainText("SUCCEEDED", { timeout: 60_000 });
  await expect(page.locator(".verification-section")).toContainText("PASSED");
});

test("真实 API 强制认证、角色、幂等与源码路径边界", async ({ request }) => {
  const session = await demoSession(request);
  const base = "http://127.0.0.1:18080/api/v1";
  expect((await request.get(`${base}/projects`)).status()).toBe(401);
  expect((await request.post(`${base}/projects/${session.project.id}/tasks`, {
    headers: { Authorization: `Bearer ${session.approver.token}` },
    data: { mode: "EXPLAIN", requirement: "forbidden", criteria: [] },
  })).status()).toBe(403);
  expect((await request.get(`${base}/projects/${session.project.id}/source`, {
    headers: { Authorization: `Bearer ${session.developer.token}` },
    params: { revision: session.project.revision, path: "../pom.xml", startLine: 1, endLine: 1 },
  })).status()).toBe(400);

  const headers = { Authorization: `Bearer ${session.developer.token}` };
  const task = await (await request.post(`${base}/projects/${session.project.id}/tasks`, {
    headers,
    data: { mode: "EXPLAIN", requirement: "E2E API 幂等契约", criteria: [] },
  })).json();
  const run = await (await request.post(`${base}/tasks/${task.id}/runs`, { headers })).json();
  const commandHeaders = { ...headers, "Idempotency-Key": `e2e-${run.id}`, "If-Match": String(run.version) };
  const first = await request.post(`${base}/runs/${run.id}/execute`, { headers: commandHeaders });
  const second = await request.post(`${base}/runs/${run.id}/execute`, { headers: commandHeaders });
  expect(first.status()).toBe(202);
  expect(second.status()).toBe(202);
  expect((await first.json()).id).toBe((await second.json()).id);
});

test("键盘、窄屏和自动化无障碍扫描无严重问题", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await ready(page);
  await page.getByRole("button", { name: "打开任务列表" }).click();
  await expect(page.locator(".sidebar.mobile-open")).toBeVisible();
  await page.getByRole("button", { name: "新建任务" }).focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("dialog", { name: "新建任务" })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "任务描述" })).toBeFocused();
  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations.filter((item) => ["critical", "serious"].includes(item.impact ?? ""))).toEqual([]);
});

import { expect, test, type Page } from "@playwright/test";

const FIXTURE_REVISION = "ee79b3282e22aaf419a137666a57a2b1b3f4f875";

async function ready(page: Page) {
  await page.goto("/");
  await expect(page.locator(".topbar")).toContainText("API 已连接");
  await expect(page.locator(".topbar")).toContainText(FIXTURE_REVISION.slice(0, 7));
  await expect(page.getByRole("button", { name: "新建任务" })).toBeEnabled();
}

async function createTask(
  page: Page,
  mode: "讲解业务" | "分析需求" | "修改代码",
  prompt: string,
) {
  await page.getByRole("button", { name: "新建任务" }).click();
  const dialog = page.getByRole("dialog", { name: "新建任务" });
  await dialog.getByRole("button", { name: new RegExp(mode) }).click();
  await dialog.getByRole("textbox", { name: "任务描述" }).fill(prompt);
  await dialog.getByRole("button", { name: "创建并运行" }).click();
  await expect(page.locator(".conversation-user").last()).toContainText(prompt);
}

test("候选面试演示从问题、证据、决策到审批验证完整闭环", async ({ page }, testInfo) => {
  test.setTimeout(120_000);
  const run = testInfo.repeatEachIndex + 1;
  await ready(page);

  await createTask(page, "讲解业务", `候选演练${run}：解释转账保存调用链`);
  await expect(page.getByText("转账服务创建包含账户、金额和时间的记录后交给仓储保存。")).toBeVisible();
  await page.getByRole("button", { name: /查看证据/ }).first().click();
  await expect(page.getByText("源码事实已与 Agent 证据核对")).toBeVisible();
  await page.getByRole("button", { name: "关闭源码证据" }).click();

  await createTask(page, "分析需求", `候选演练${run}：分析转账限额影响面`);
  await expect(page.getByText("转账写入集中在 TransferService，并直接调用仓储保存。")).toBeVisible();

  await createTask(page, "修改代码", `[INTERVIEW_DEMO_CHANGE] 候选演练${run}`);
  await expect(page.getByText("CHANGE 决策工作台")).toBeVisible();
  await expect(page.locator(".diff-section")).toContainText("Candidate changes must pass governed review");
  await page.getByRole("textbox", { name: "审批说明 / 拒绝理由" }).fill("独立审批人已核对计划、差异与双 digest");
  await page.getByRole("button", { name: "批准并继续" }).click();
  await expect(page.locator(".task-header .eyebrow-row")).toContainText("SUCCEEDED", { timeout: 60_000 });
  await expect(page.getByText("已由独立审批人批准")).toBeVisible();
  await expect(page.locator(".verification-section")).toContainText("PASSED");
});

import { defineConfig, devices } from "@playwright/test";

process.env.NO_PROXY = "127.0.0.1,localhost";
process.env.no_proxy = process.env.NO_PROXY;

const repositoryRoot = new URL("../..", import.meta.url).pathname;

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  timeout: 45_000,
  expect: { timeout: 15_000 },
  outputDir: "output/playwright/results",
  reporter: [
    ["list"],
    ["html", { outputFolder: "output/playwright/report", open: "never" }],
    ["json", { outputFile: "output/playwright/results.json" }],
  ],
  use: {
    baseURL: "http://127.0.0.1:5173",
    channel: "chromium",
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
    video: "retain-on-failure",
    ...devices["Desktop Chrome"],
  },
  webServer: [
    {
      command: "node e2e/model-server.mjs",
      url: "http://127.0.0.1:12435/health",
      reuseExistingServer: false,
      stdout: "pipe",
      stderr: "pipe",
    },
    {
      command: "node e2e/server-supervisor.mjs",
      url: "http://127.0.0.1:18081/health",
      reuseExistingServer: false,
      timeout: 60_000,
      stdout: "pipe",
      stderr: "pipe",
    },
    {
      command: "VITE_API_PROXY_TARGET=http://127.0.0.1:18080 npm run dev -- --port 5173",
      url: "http://127.0.0.1:5173",
      reuseExistingServer: false,
      stdout: "pipe",
      stderr: "pipe",
    },
  ],
});

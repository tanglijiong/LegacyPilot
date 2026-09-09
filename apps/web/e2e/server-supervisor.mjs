import { createServer } from "node:http";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { spawn } from "node:child_process";

const repositoryRoot = resolve(import.meta.dirname, "../../..");
const runtimeRoot = mkdtempSync(join(tmpdir(), "legacy-pilot-e2e-restart."));
const serverLog = resolve(repositoryRoot, "apps/web/output/playwright/server.log");
const controlPort = 18081;
let application;
let restarting = false;

writeFileSync(serverLog, "", "utf8");

function startApplication() {
  application = spawn(resolve(repositoryRoot, "deploy/e2e/start-server.sh"), [], {
    cwd: repositoryRoot,
    env: {
      ...process.env,
      LEGACY_PILOT_E2E_RUNTIME_ROOT: runtimeRoot,
      LEGACY_PILOT_AGENT_LEASE_TTL: "4s",
      LEGACY_PILOT_COMMAND_LEASE_TTL: "5s",
      LEGACY_PILOT_QUEUE_SCAN_INTERVAL: "500ms",
      LEGACY_PILOT_MAXIMUM_RETRIES: "0",
    },
    stdio: "inherit",
  });
  application.once("exit", (code, signal) => {
    if (!restarting && code !== 0 && signal !== "SIGTERM") {
      process.stderr.write(`E2E application exited unexpectedly: code=${code} signal=${signal}\n`);
    }
  });
}

async function applicationReady() {
  if (!application || application.exitCode !== null || application.signalCode !== null) return false;
  try {
    const response = await fetch("http://127.0.0.1:18080/api/v1/demo/session", {
      signal: AbortSignal.timeout(500),
    });
    return response.ok;
  } catch {
    return false;
  }
}

async function waitUntilReady() {
  for (let attempt = 0; attempt < 120; attempt += 1) {
    if (await applicationReady()) return;
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 250));
  }
  throw new Error("restarted E2E application did not become ready within 30 seconds");
}

function stopApplication(signal = "SIGTERM") {
  return new Promise((resolveStop) => {
    if (!application || application.exitCode !== null || application.signalCode !== null) {
      resolveStop();
      return;
    }
    application.once("exit", resolveStop);
    application.kill(signal);
  });
}

startApplication();

const control = createServer(async (request, response) => {
  if (request.method === "GET" && request.url === "/health") {
    const ready = await applicationReady();
    response.writeHead(ready ? 200 : 503, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ ready, restarting }));
    return;
  }
  if (request.method === "POST" && request.url === "/restart") {
    if (restarting) {
      response.writeHead(409, { "Content-Type": "application/json" });
      response.end('{"error":"restart already in progress"}');
      return;
    }
    restarting = true;
    try {
      await stopApplication("SIGKILL");
      startApplication();
      await waitUntilReady();
      response.writeHead(200, { "Content-Type": "application/json" });
      response.end('{"restarted":true}');
    } catch (error) {
      response.writeHead(500, { "Content-Type": "application/json" });
      response.end(JSON.stringify({ error: error.message }));
    } finally {
      restarting = false;
    }
    return;
  }
  response.writeHead(404).end();
}).listen(controlPort, "127.0.0.1");

async function shutdown() {
  control.close();
  await stopApplication();
  process.exit(0);
}

process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);

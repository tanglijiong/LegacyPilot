import { createServer } from "node:http";

const failedRequirements = new Set();
let verificationRecoveryState = "INITIAL";
const transferServicePath = "src/main/java/io/legacypilot/samples/banking/TransferService.java";
const transferServiceSha256 = "4650c5cda8fcd8a061cd5a8c4614220741a9ddf551459f08ecc3a903c5386b15";
const interviewDemoReplacement = `package io.legacypilot.samples.banking;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Baseline transfer service. Candidate changes must pass governed review and verification. */
@Service
public final class TransferService {
  private final TransferRepository repository;
  private final AccountRepository accounts;
  private final CustomerRepository customers;
  private final Clock clock;

  public TransferService(
      TransferRepository repository,
      AccountRepository accounts,
      CustomerRepository customers,
      Clock clock) {
    this.repository = Objects.requireNonNull(repository);
    this.accounts = Objects.requireNonNull(accounts);
    this.customers = Objects.requireNonNull(customers);
    this.clock = Objects.requireNonNull(clock);
  }

  public TransferRecord transfer(String accountId, BigDecimal amount) {
    var transfer =
        new TransferRecord(UUID.randomUUID().toString(), accountId, amount, clock.instant());
    repository.save(transfer);
    return transfer;
  }

  public java.util.List<TransferRecord> history(
      String accountId, Instant fromInclusive, Instant toExclusive) {
    return repository.findByAccountIdAndOccurredAtBetween(accountId, fromInclusive, toExclusive);
  }
}
`;
const invalidVerificationReplacement = `package io.legacypilot.samples.banking;

/** Intentionally invalid E2E verification fixture. */
public final class TransferService {
  this does not compile
}
`;
const invalidVerificationSha256 = "252534414081d5a47adefdc4d7317dc63375b4e155d0866a3fd082aaa45f1861";
function completion(content) {
  return JSON.stringify({
    choices: [{ message: { role: "assistant", content: JSON.stringify(content) } }],
    usage: { prompt_tokens: 120, completion_tokens: 80 },
  });
}

function plan(mode) {
  return {
    version: 1,
    steps: mode === "CHANGE" ? ["准备受治理变更", "等待独立审批", "执行验证"] : ["定位入口", "追踪调用", "提交源码依据结论"],
    affectedFiles: ["src/main/java/io/legacypilot/samples/banking/TransferService.java"],
    risk: mode === "CHANGE" ? "MEDIUM" : "LOW",
    rationale: mode === "CHANGE" ? "通过审批边界验证真实变更工作流。" : "从固定 revision 的转账服务提取可复核事实。",
  };
}

function readOnlyResult(mode) {
  const sectionNames = mode === "ANALYZE"
    ? ["entryPoints", "callPaths", "affectedComponents", "businessRules", "dataOperations", "risks", "recommendedTests"]
    : ["actors", "triggers", "mainFlow", "decisionRules", "stateChanges", "dataChanges", "exceptionFlows", "glossary"];
  const claims = sectionNames.map((name) => `${name}: TransferService.transfer creates and saves a TransferRecord.`);
  return {
    type: "SUBMIT_RESULT",
    reason: "调查完成，提交固定 revision 的结构化结果。",
    input: {
      summary: mode === "ANALYZE" ? "转账写入集中在 TransferService，并直接调用仓储保存。" : "转账服务创建包含账户、金额和时间的记录后交给仓储保存。",
      sections: Object.fromEntries(sectionNames.map((name, index) => [name, [{
        claim: claims[index],
        symbol: "io.legacypilot.samples.banking.TransferService",
      }]])),
      unknowns: ["E2E fixture does not define production persistence behavior."],
    },
  };
}

function action(prompt) {
  const mode = prompt.match(/Task mode: (EXPLAIN|ANALYZE|CHANGE)/)?.[1] ?? "ANALYZE";
  if (!prompt.includes("Plan:")) return plan(mode);
  if (mode !== "CHANGE") {
    return readOnlyResult(mode);
  }
  if (prompt.includes("[E2E_VERIFY_FAIL_ONCE]")) {
    if (verificationRecoveryState === "INITIAL") {
      verificationRecoveryState = "INVALID_WRITTEN";
      return {
        type: "TOOL",
        tool: "apply_patch",
        reason: "先注入无法编译的受治理变更，用真实 Maven 门禁证明验证失败可恢复。",
        input: {
          path: transferServicePath,
          expectedSha256: transferServiceSha256,
          replacement: invalidVerificationReplacement,
        },
      };
    }
    if (verificationRecoveryState === "INVALID_WRITTEN") {
      verificationRecoveryState = "VERIFICATION_FAILED";
      return { type: "VERIFY", reason: "执行真实 Maven 门禁并记录预期的编译失败。" };
    }
    if (verificationRecoveryState === "VERIFICATION_FAILED") {
      verificationRecoveryState = "REPAIRED";
      return {
        type: "TOOL",
        tool: "apply_patch",
        reason: "重新规划后修复编译错误，并要求新的独立审批。",
        input: {
          path: transferServicePath,
          expectedSha256: invalidVerificationSha256,
          replacement: interviewDemoReplacement,
        },
      };
    }
    return { type: "VERIFY", reason: "修复已完成，重新运行真实 Maven 门禁。" };
  }
  if (prompt.includes("[INTERVIEW_DEMO_CHANGE]") && prompt.includes("bytesWritten")) {
    return { type: "VERIFY", reason: "受治理写入已完成，执行编译、测试和静态检查。" };
  }
  if (prompt.includes("[INTERVIEW_DEMO_CHANGE]")) {
    return {
      type: "TOOL",
      tool: "apply_patch",
      reason: "候选演练通过可见差异验证 digest 绑定、独立审批和验证闭环。",
      input: {
        path: transferServicePath,
        expectedSha256: transferServiceSha256,
        replacement: interviewDemoReplacement,
      },
    };
  }
  return {
    type: "TOOL",
    tool: "apply_patch",
    reason: "E2E 变更必须经过独立审批。",
    input: {
      path: transferServicePath,
      expectedSha256: "0000000000000000000000000000000000000000000000000000000000000000",
      replacement: "package io.legacypilot.samples.banking;\n",
    },
  };
}

createServer((request, response) => {
  if (request.url === "/health") {
    response.writeHead(200, { "Content-Type": "text/plain" });
    response.end("ok");
    return;
  }
  if (request.method !== "POST" || request.url !== "/v1/chat/completions") {
    response.writeHead(404).end();
    return;
  }
  let body = "";
  request.on("data", (chunk) => { body += chunk; });
  request.on("end", () => {
    const payload = JSON.parse(body);
    const prompt = payload.messages?.at(-1)?.content ?? "";
    const requirement = prompt.match(/Requirement(?::|:\n)\s*([^\n]+)/)?.[1] ?? prompt;
    if (prompt.includes("[E2E_FAIL_ONCE]") && !failedRequirements.has(requirement)) {
      failedRequirements.add(requirement);
      response.writeHead(400, { "Content-Type": "application/json" });
      response.end('{"error":"deterministic E2E model failure"}');
      return;
    }
    const delay = prompt.includes("[E2E_SLOW]") ? 1_500 : 0;
    setTimeout(() => {
      response.writeHead(200, { "Content-Type": "application/json" });
      response.end(completion(action(prompt)));
    }, delay);
  });
}).listen(12435, "127.0.0.1");

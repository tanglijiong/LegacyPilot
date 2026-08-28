package io.legacypilot.tool.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolicyDslTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path temporary;

  @Test
  void secureDefaultIsCompatibleAndExplainsRules() {
    var policy = new DefaultExecutionPolicy();
    for (var risk : RiskLevel.values()) {
      for (var commandAllowed : new boolean[] {false, true}) {
        var descriptor = descriptor("test_tool", risk, Idempotency.IDEMPOTENT);
        var context = new ToolContext("run", temporary, Set.of(), commandAllowed);
        var decision = policy.evaluate(descriptor, context, MAPPER.createObjectNode());
        var expected =
            switch (risk) {
              case READ_ONLY -> PolicyDecision.Effect.ALLOW;
              case WORKSPACE_WRITE -> PolicyDecision.Effect.REQUIRE_APPROVAL;
              case COMMAND_EXECUTION ->
                  commandAllowed
                      ? PolicyDecision.Effect.ALLOW
                      : PolicyDecision.Effect.REQUIRE_APPROVAL;
              case EXTERNAL_IO -> PolicyDecision.Effect.DENY;
            };
        assertEquals(expected, decision.effect());
        assertEquals("secure-default-v1", decision.policyRevision());
        assertFalse(decision.ruleId().isBlank());
      }
    }
  }

  @Test
  void loadsYamlAndKeepsLastGoodPolicyWhenReloadFails() throws Exception {
    var loader = new PolicyLoader(PolicyLoader.secureDefault());
    var source = temporary.resolve("policy.yml");
    Files.writeString(
        source,
        """
        schemaVersion: 1
        revision: repo-policy-1
        rules:
          - id: deny-secrets
            effect: DENY
            tools: [file.write]
            pathPrefixes: [config/secrets]
            reason: protected path
          - id: approve-writes
            effect: REQUIRE_APPROVAL
            risks: [WORKSPACE_WRITE]
            reason: approval required
            requiredScope: action
        """);
    assertTrue(loader.reload(source));
    var descriptor = descriptor("file.write", RiskLevel.WORKSPACE_WRITE, Idempotency.CONDITIONAL);
    var input = MAPPER.readTree("{\"path\":\"config/secrets/key.txt\"}");
    var denied = loader.evaluate(descriptor, context(Set.of()), input);
    assertEquals(PolicyDecision.Effect.DENY, denied.effect());
    assertEquals("deny-secrets", denied.ruleId());

    Files.writeString(source, "schemaVersion: 99\nrevision: future\nrules: []\n");
    assertFalse(loader.reload(source));
    assertEquals("repo-policy-1", loader.activeDocument().revision());
    assertFalse(loader.lastError().contains("future"));
  }

  @Test
  void approvalIsBoundToDigestAndPathRulesFailClosed() throws Exception {
    var policy =
        new ConfigurableExecutionPolicy(
            new PolicyDocument(
                1,
                "matrix-1",
                java.util.List.of(
                    new PolicyRule(
                        "approve-src",
                        PolicyDecision.Effect.REQUIRE_APPROVAL,
                        Set.of("file.*"),
                        Set.of(RiskLevel.WORKSPACE_WRITE),
                        Set.of(),
                        Set.of("src"),
                        null,
                        0,
                        "source write",
                        "action"))));
    var descriptor = descriptor("file.write", RiskLevel.WORKSPACE_WRITE, Idempotency.CONDITIONAL);
    var input = MAPPER.readTree("{\"path\":\"src/main/App.java\"}");
    var required = policy.evaluate(descriptor, context(Set.of()), input);
    assertEquals(PolicyDecision.Effect.REQUIRE_APPROVAL, required.effect());
    assertEquals(
        PolicyDecision.Effect.ALLOW,
        policy.evaluate(descriptor, context(Set.of(required.actionDigest())), input).effect());
    assertEquals(
        PolicyDecision.Effect.DENY,
        policy
            .evaluate(
                descriptor,
                context(Set.of(required.actionDigest())),
                MAPPER.readTree("{\"path\":\"../outside\"}"))
            .effect());
  }

  @Test
  void evaluatesFiftyDeterministicSecurityMatrixCases() {
    var policy = new DefaultExecutionPolicy();
    IntStream.range(0, 50)
        .forEach(
            index -> {
              var risk = RiskLevel.values()[index % RiskLevel.values().length];
              var decision =
                  policy.evaluate(
                      descriptor("tool_" + index, risk, Idempotency.values()[index % 3]),
                      new ToolContext("run-" + index, temporary, Set.of(), index % 2 == 0),
                      MAPPER.createObjectNode().put("path", "src/Item" + index + ".java"));
              assertFalse(decision.actionDigest().isBlank());
              assertFalse(decision.ruleId().isBlank());
              if (risk == RiskLevel.EXTERNAL_IO) {
                assertEquals(PolicyDecision.Effect.DENY, decision.effect());
              }
            });
  }

  private ToolContext context(Set<String> digests) {
    return new ToolContext("run", temporary, digests, false);
  }

  private static ToolDescriptor descriptor(String name, RiskLevel risk, Idempotency idempotency) {
    return new ToolDescriptor(
        name,
        "policy test",
        MAPPER.createObjectNode(),
        MAPPER.createObjectNode(),
        risk,
        idempotency,
        Duration.ofSeconds(1),
        1024,
        1024,
        Set.of());
  }
}

package io.legacypilot.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.context.ContextBuildResult;
import io.legacypilot.model.ModelGateway;
import io.legacypilot.model.ModelRequest;
import io.legacypilot.model.ModelResult;
import io.legacypilot.tool.spi.JsonSchemas;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public final class AgentPlanner {

  private static final com.fasterxml.jackson.databind.JsonNode PLAN_SCHEMA =
      JsonSchemas.parse(
          """
          {"type":"object","required":["version","steps","affectedFiles","risk","rationale"],
           "additionalProperties":false,"properties":{"version":{"type":"integer"},
           "steps":{"type":"array","maxItems":50,"items":{"type":"string","maxLength":500}},
           "affectedFiles":{"type":"array","maxItems":100,"items":{"type":"string","maxLength":4096}},
           "risk":{"type":"string","enum":["LOW","MEDIUM","HIGH"]},
           "rationale":{"type":"string","maxLength":4000}}}
          """);
  private static final com.fasterxml.jackson.databind.JsonNode ACTION_SCHEMA =
      JsonSchemas.parse(
          """
          {"type":"object","required":["type","reason"],"additionalProperties":false,
           "properties":{"type":{"type":"string","enum":["TOOL","VERIFY","FINISH"]},
           "tool":{"type":"string","maxLength":96},"input":{"type":"object"},
           "reason":{"type":"string","maxLength":2000}}}
          """);

  private final ModelGateway models;
  private final ObjectMapper mapper;

  public AgentPlanner(ModelGateway models, ObjectMapper mapper) {
    this.models = Objects.requireNonNull(models);
    this.mapper = Objects.requireNonNull(mapper);
  }

  public ModelResult<ChangePlan> plan(AgentRunRequest run, ContextBuildResult context) {
    var prompt =
        "Requirement:\n"
            + run.requirement()
            + "\n\nRelevant code:\n"
            + context.chunks().stream()
                .map(chunk -> chunk.content())
                .collect(java.util.stream.Collectors.joining("\n\n"));
    return models.generate(request(run, prompt, PLAN_SCHEMA), ChangePlan.class);
  }

  public ModelResult<AgentAction> next(
      AgentRunRequest run, ChangePlan plan, String observation, int step) {
    var prompt =
        "Requirement: "
            + run.requirement()
            + "\nPlan: "
            + mapper.valueToTree(plan)
            + "\nStep: "
            + step
            + "\nLast observation: "
            + observation
            + "\nChoose one registered tool action or request verification.";
    return models.generate(request(run, prompt, ACTION_SCHEMA), AgentAction.class);
  }

  private static ModelRequest request(
      AgentRunRequest run, String prompt, com.fasterxml.jackson.databind.JsonNode schema) {
    return new ModelRequest(
        "You are the LegacyPilot planner. You may propose actions but cannot declare success.",
        prompt,
        schema,
        run.model(),
        0.0,
        Duration.ofSeconds(60),
        Map.of("runId", run.runId()));
  }
}

package io.legacypilot.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.context.ContextBuildResult;
import io.legacypilot.model.ModelGateway;
import io.legacypilot.model.ModelRequest;
import io.legacypilot.model.ModelResult;
import io.legacypilot.tool.spi.JsonSchemas;
import io.legacypilot.tool.spi.ToolDescriptor;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
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
  private static final com.fasterxml.jackson.databind.JsonNode READ_ONLY_ACTION_SCHEMA =
      JsonSchemas.parse(
          """
          {"type":"object","required":["type","reason"],"additionalProperties":false,
           "properties":{"type":{"type":"string","enum":["SUBMIT_RESULT"]},
           "input":{"type":"object","required":["summary","sections","unknowns"],"properties":{
             "summary":{"type":"string","minLength":1,"maxLength":8000},
             "sections":{"type":"object","additionalProperties":{
               "type":"array","items":{"type":"object",
               "required":["claim","symbol"],
               "additionalProperties":false,"properties":{
                 "symbol":{"type":"string","minLength":1,"maxLength":1024},
                 "claim":{"type":"string","minLength":1,"maxLength":4000}}}}},
             "unknowns":{"type":"array","items":{"type":"string","maxLength":4000}}},
             "additionalProperties":false},
           "reason":{"type":"string","maxLength":2000}}}
          """);

  private final ModelGateway models;
  private final ObjectMapper mapper;
  private final List<ToolDescriptor> tools;

  public AgentPlanner(ModelGateway models, ObjectMapper mapper) {
    this(models, mapper, List.of());
  }

  public AgentPlanner(ModelGateway models, ObjectMapper mapper, Collection<ToolDescriptor> tools) {
    this.models = Objects.requireNonNull(models);
    this.mapper = Objects.requireNonNull(mapper);
    this.tools =
        Objects.requireNonNull(tools).stream()
            .filter(
                descriptor ->
                    descriptor.risk() != io.legacypilot.tool.spi.RiskLevel.COMMAND_EXECUTION)
            .sorted(java.util.Comparator.comparing(ToolDescriptor::name))
            .toList();
  }

  public ModelResult<ChangePlan> plan(AgentRunRequest run, ContextBuildResult context) {
    var prompt =
        "Task mode: "
            + run.mode()
            + "\n"
            + planInstructions(run)
            + "\nRequirement:\n"
            + run.requirement()
            + "\n\nRelevant code:\n"
            + context.chunks().stream()
                .map(chunk -> chunk.content())
                .collect(java.util.stream.Collectors.joining("\n\n"));
    return models.generate(request(run, prompt, PLAN_SCHEMA), ChangePlan.class);
  }

  private static String planInstructions(AgentRunRequest run) {
    if (!run.mode().readOnly()) {
      return "Plan a governed code change using only managed-workspace source reads, patches, and "
          + "automatic local verification. Do not plan deployment, production changes, pressure "
          + "testing, external monitoring systems, or other unavailable infrastructure. Keep the "
          + "change minimal: do not modify an existing launcher or unrelated file unless the user "
          + "explicitly requests that integration. Include only concrete code-edit and local "
          + "verification steps.";
    }
    return "Plan a read-only investigation. The steps must locate entry points, follow relevant "
        + "symbols and dependencies, inspect business rules and data effects, and finish with a "
        + "source-grounded result. Do not plan file edits, patches, builds that generate files, or "
        + "any other workspace mutation.";
  }

  public ModelResult<AgentAction> next(
      AgentRunRequest run,
      ChangePlan plan,
      String observation,
      int step,
      ContextBuildResult context) {
    var allowedSymbols = allowedSymbols(run, context);
    var modeInstructions = modeInstructions(run, allowedSymbols, observation);
    var relevantCode =
        run.mode().readOnly()
            ? context.chunks().stream()
                .map(chunk -> chunk.content())
                .collect(java.util.stream.Collectors.joining("\n\n"))
            : "Use the bounded source tools from the registered catalog.";
    var prompt =
        "Task mode: "
            + run.mode()
            + "\nRequirement: "
            + run.requirement()
            + "\nPlan: "
            + mapper.valueToTree(plan)
            + "\nStep: "
            + step
            + "\nLast observation: "
            + observation
            + "\nRelevant code at fixed revision "
            + run.projectIndex().revision()
            + ":\n"
            + relevantCode
            + "\n"
            + modeInstructions;
    var schema =
        run.mode().readOnly()
            ? readOnlyActionSchema(allowedSymbols)
            : changeActionSchema(observation);
    return models.generate(request(run, prompt, schema), AgentAction.class);
  }

  private com.fasterxml.jackson.databind.JsonNode changeActionSchema(String observation) {
    if (tools.isEmpty()) {
      return ACTION_SCHEMA;
    }
    var schema = mapper.createObjectNode();
    var alternatives = schema.putArray("oneOf");
    availableTools(observation)
        .forEach(
            descriptor -> {
              var action = alternatives.addObject();
              action.put("type", "object");
              action.put("additionalProperties", false);
              var required = action.putArray("required");
              required.add("type").add("tool").add("input").add("reason");
              var properties = action.putObject("properties");
              properties.putObject("type").put("type", "string").putArray("enum").add("TOOL");
              properties
                  .putObject("tool")
                  .put("type", "string")
                  .putArray("enum")
                  .add(descriptor.name());
              properties.set("input", descriptor.inputSchema());
              properties.putObject("reason").put("type", "string").put("maxLength", 2000);
            });
    var terminal = alternatives.addObject();
    terminal.put("type", "object");
    terminal.put("additionalProperties", false);
    terminal.putArray("required").add("type").add("reason");
    var terminalProperties = terminal.putObject("properties");
    terminalProperties
        .putObject("type")
        .put("type", "string")
        .putArray("enum")
        .add("VERIFY")
        .add("FINISH");
    terminalProperties.putObject("reason").put("type", "string").put("maxLength", 2000);
    return schema;
  }

  private List<ToolDescriptor> availableTools(String observation) {
    var patchPrepared =
        observation != null
            && observation.contains("\"expectedSha256\"")
            && observation.contains("\"replacement\"");
    if (patchPrepared) {
      return tools.stream().filter(descriptor -> descriptor.name().equals("apply_patch")).toList();
    }
    return tools.stream().filter(descriptor -> !descriptor.name().equals("apply_patch")).toList();
  }

  private static List<String> allowedSymbols(AgentRunRequest run, ContextBuildResult context) {
    var contextualIds =
        context.chunks().stream()
            .map(io.legacypilot.context.ContextChunk::symbolId)
            .collect(java.util.stream.Collectors.toSet());
    var contextual =
        run.projectIndex().symbols().stream()
            .filter(symbol -> contextualIds.contains(symbol.id()))
            .map(io.legacypilot.analysis.java.SourceSymbol::qualifiedName)
            .filter(name -> !name.isBlank())
            .distinct()
            .limit(100)
            .toList();
    if (!contextual.isEmpty()) {
      return contextual;
    }
    return run.projectIndex().symbols().stream()
        .filter(symbol -> !symbol.testSource())
        .map(io.legacypilot.analysis.java.SourceSymbol::qualifiedName)
        .filter(name -> !name.isBlank())
        .distinct()
        .limit(100)
        .toList();
  }

  private static com.fasterxml.jackson.databind.JsonNode readOnlyActionSchema(
      List<String> allowedSymbols) {
    var schema = READ_ONLY_ACTION_SCHEMA.deepCopy();
    var symbolSchema =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            schema.at(
                "/properties/input/properties/sections/additionalProperties/items/properties/symbol");
    var values = symbolSchema.putArray("enum");
    allowedSymbols.forEach(values::add);
    return schema;
  }

  private String modeInstructions(
      AgentRunRequest run, List<String> allowedSymbols, String observation) {
    if (!run.mode().readOnly()) {
      return changeInstructions(observation);
    }
    var sections =
        run.mode() == io.legacypilot.domain.task.TaskMode.ANALYZE
            ? "entryPoints, callPaths, affectedComponents, businessRules, dataOperations, risks, recommendedTests"
            : "actors, triggers, mainFlow, decisionRules, stateChanges, dataChanges, exceptionFlows, glossary";
    return "All relevant source is already supplied above; do not request or invent a tool. "
        + "Choose SUBMIT_RESULT now. Its input must contain summary, sections, and unknowns. "
        + "Required non-empty section names: "
        + sections
        + ". Each section entry is an object with a short factual claim and the exact qualified "
        + "symbol copied from a supplied Relevant code header. The server binds that symbol to its "
        + "canonical path, lines, excerpt and revision; never invent or shorten a symbol. When "
        + "requested behavior is absent, make the claim describe what the supplied code actually "
        + "does and put the missing behavior in unknowns. Allowed symbol values are exactly: "
        + allowedSymbols
        + ". Return no empty strings.";
  }

  private String changeInstructions(String observation) {
    if (tools.isEmpty()) {
      return "Choose one registered tool action or request verification.";
    }
    var catalog = mapper.createArrayNode();
    availableTools(observation)
        .forEach(
            descriptor -> {
              var item = catalog.addObject();
              item.put("name", descriptor.name());
              item.put("description", descriptor.description());
              item.put("risk", descriptor.risk().name());
            });
    return "Choose only a tool name from the registered tool catalog below, or choose VERIFY. "
        + "A Java class, method, file, command, or plan step is never a tool name. Inspect the "
        + "relevant existing target range with read_file before proposing a change. If a planned "
        + "target file does not exist, do not call read_file for it: call create_patch directly with "
        + "one complete, compilable and functional file that fully satisfies the requested behavior; "
        + "omit startLine/endLine and do not submit a scaffold, skeleton, or controls without their "
        + "behavior. read_file is intentionally "
        + "bounded; when it returns nextStartLine, continue from that line and never repeat the same "
        + "path/startLine/endLine request. To edit an existing file, call create_patch with "
        + "the inclusive startLine/endLine range and only the complete compilable replacement for "
        + "those lines. Preserve surrounding syntax; summaries and placeholders are forbidden. "
        + "Then call apply_patch with every patch field returned by create_patch, including the line "
        + "range when present. Once create_patch succeeds, apply_patch is the only available tool; "
        + "never create another candidate patch. The write will pause for independent approval. "
        + "After applying changes, "
        + "choose VERIFY; compilation, tests, and static analysis are then run automatically in "
        + "the verification phase. Exact tool inputs are enforced by the response schema. "
        + "Registered tool catalog:\n"
        + catalog;
  }

  private static ModelRequest request(
      AgentRunRequest run, String prompt, com.fasterxml.jackson.databind.JsonNode schema) {
    return new ModelRequest(
        "You are the LegacyPilot planner. You may propose actions but cannot declare success. "
            + ResponseLanguage.instruction(run.requirement()),
        prompt,
        schema,
        run.model(),
        0.0,
        Duration.ofSeconds(60),
        Map.of("runId", run.runId()));
  }
}

package io.legacypilot.tool.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.tool.spi.AgentTool;
import io.legacypilot.tool.spi.Idempotency;
import io.legacypilot.tool.spi.JsonSchemas;
import io.legacypilot.tool.spi.RiskLevel;
import io.legacypilot.tool.spi.ToolContext;
import io.legacypilot.tool.spi.ToolDescriptor;
import io.legacypilot.tool.spi.ToolErrorCode;
import io.legacypilot.tool.spi.ToolFailureException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class GitDiffTool implements AgentTool {

  private static final int MAX_OUTPUT = 1024 * 1024;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "git_diff",
          "Return the current workspace diff and change statistics without external diff drivers.",
          JsonSchemas.parse("{\"type\":\"object\",\"additionalProperties\":false}"),
          JsonSchemas.parse("{\"type\":\"object\"}"),
          RiskLevel.READ_ONLY,
          Idempotency.IDEMPOTENT,
          Duration.ofSeconds(10),
          1024,
          2 * MAX_OUTPUT,
          Set.of());

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public JsonNode execute(ToolContext context, JsonNode input) {
    var diff = run(context, List.of("diff", "--no-ext-diff", "--no-color", "HEAD", "--"));
    var stat =
        run(context, List.of("diff", "--no-ext-diff", "--no-color", "--numstat", "HEAD", "--"));
    var output = MAPPER.createObjectNode();
    output.put("diff", diff.text());
    output.put("numstat", stat.text());
    output.put("truncated", diff.truncated() || stat.truncated());
    return output;
  }

  private Output run(ToolContext context, List<String> arguments) {
    var command = new java.util.ArrayList<String>();
    command.add("git");
    command.addAll(arguments);
    try {
      var process =
          new ProcessBuilder(command)
              .directory(context.workspaceRoot().toFile())
              .redirectErrorStream(true)
              .start();
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var output = executor.submit(() -> read(process));
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          throw new ToolFailureException(ToolErrorCode.TIMEOUT, "Git diff timed out");
        }
        var result = output.get();
        if (process.exitValue() != 0) {
          throw new ToolFailureException(
              ToolErrorCode.COMMAND_FAILED, "Git diff failed: " + result.text());
        }
        return result;
      }
    } catch (IOException exception) {
      throw new ToolFailureException(
          ToolErrorCode.COMMAND_FAILED, "Unable to start Git diff", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ToolFailureException(
          ToolErrorCode.COMMAND_FAILED, "Git diff interrupted", exception);
    } catch (java.util.concurrent.ExecutionException exception) {
      throw new ToolFailureException(
          ToolErrorCode.COMMAND_FAILED, "Unable to collect Git diff", exception);
    }
  }

  private static Output read(Process process) throws IOException {
    var bytes = new byte[MAX_OUTPUT];
    var length = 0;
    var truncated = false;
    try (var stream = process.getInputStream()) {
      int value;
      while ((value = stream.read()) >= 0) {
        if (length < MAX_OUTPUT) {
          bytes[length++] = (byte) value;
        } else {
          truncated = true;
        }
      }
    }
    return new Output(new String(bytes, 0, length, StandardCharsets.UTF_8), truncated);
  }

  private record Output(String text, boolean truncated) {}
}

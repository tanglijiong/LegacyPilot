package io.legacypilot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Objects;

public final class McpStdioServer {
  public static final String PROTOCOL_VERSION = "2025-06-18";

  private final BufferedReader input;
  private final PrintWriter output;
  private final McpToolBridge tools;
  private final ObjectMapper mapper;
  private final String serverVersion;

  public McpStdioServer(
      BufferedReader input, PrintWriter output, McpToolBridge tools, ObjectMapper mapper) {
    this(input, output, tools, mapper, runtimeVersion());
  }

  McpStdioServer(
      BufferedReader input,
      PrintWriter output,
      McpToolBridge tools,
      ObjectMapper mapper,
      String serverVersion) {
    this.input = Objects.requireNonNull(input);
    this.output = Objects.requireNonNull(output);
    this.tools = Objects.requireNonNull(tools);
    this.mapper = Objects.requireNonNull(mapper);
    this.serverVersion = Objects.requireNonNull(serverVersion);
  }

  public void serve() throws IOException {
    String line;
    while ((line = input.readLine()) != null) {
      if (!line.isBlank()) {
        handleLine(line);
      }
    }
  }

  void handleLine(String line) {
    JsonNode request;
    try {
      request = mapper.readTree(line);
    } catch (IOException exception) {
      write(error(null, -32700, "Parse error"));
      return;
    }
    var id = request.get("id");
    var method = request.path("method").asText();
    if (method.equals("notifications/initialized")) {
      return;
    }
    JsonNode result;
    switch (method) {
      case "initialize" -> result = initialize();
      case "tools/list" -> result = mapper.createObjectNode().set("tools", tools.listTools());
      case "tools/call" -> {
        var params = request.path("params");
        result = tools.call(params.path("name").asText(), params.path("arguments"));
      }
      default -> {
        write(error(id, -32601, "Method not found"));
        return;
      }
    }
    var response = mapper.createObjectNode().put("jsonrpc", "2.0");
    response.set("id", id == null ? mapper.nullNode() : id);
    response.set("result", result);
    write(response);
  }

  private JsonNode initialize() {
    var result = mapper.createObjectNode();
    result.put("protocolVersion", PROTOCOL_VERSION);
    result.putObject("capabilities").putObject("tools").put("listChanged", false);
    result
        .putObject("serverInfo")
        .put("name", "legacy-pilot-java-project")
        .put("version", serverVersion);
    return result;
  }

  private static String runtimeVersion() {
    var implementationVersion = McpStdioServer.class.getPackage().getImplementationVersion();
    return implementationVersion == null || implementationVersion.isBlank()
        ? "development"
        : implementationVersion;
  }

  private JsonNode error(JsonNode id, int code, String message) {
    var response = mapper.createObjectNode().put("jsonrpc", "2.0");
    response.set("id", id == null ? mapper.nullNode() : id);
    response.putObject("error").put("code", code).put("message", message);
    return response;
  }

  private void write(JsonNode value) {
    output.println(value);
    output.flush();
  }
}

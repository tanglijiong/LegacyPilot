package io.legacypilot.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.observability.InMemoryTraceSink;
import io.legacypilot.observability.SensitiveDataRedactor;
import io.legacypilot.sandbox.DockerSandbox;
import io.legacypilot.sandbox.SandboxLimits;
import io.legacypilot.tool.filesystem.ReadFileTool;
import io.legacypilot.tool.filesystem.SearchCodeTool;
import io.legacypilot.tool.git.GitDiffTool;
import io.legacypilot.tool.maven.MavenTool;
import io.legacypilot.tool.spi.DefaultExecutionPolicy;
import io.legacypilot.tool.spi.ToolExecutor;
import io.legacypilot.tool.spi.ToolRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;

public final class LegacyPilotMcpMain {
  private LegacyPilotMcpMain() {}

  public static void main(String[] arguments) throws java.io.IOException {
    if (arguments.length != 1) {
      throw new IllegalArgumentException("Usage: LegacyPilotMcpMain <fixed-workspace>");
    }
    var workspace = Path.of(arguments[0]).toRealPath();
    var mapper = new ObjectMapper();
    var sandbox = DockerSandbox.secureMavenDefaults();
    var limits = SandboxLimits.safeDefaults();
    var cache = Path.of(System.getProperty("user.home"), ".m2", "repository");
    var registry =
        new ToolRegistry(
            List.of(
                new ReadFileTool(),
                new SearchCodeTool(),
                SearchCodeTool.findReferences(),
                new GitDiffTool(),
                MavenTool.compile(sandbox, cache, Set.of(), Set.of(), limits),
                MavenTool.tests(sandbox, cache, Set.of(), Set.of(), limits)));
    var executor = new ToolExecutor(registry, new DefaultExecutionPolicy(), mapper);
    var trace = new InMemoryTraceSink(new SensitiveDataRedactor(8_192));
    var bridge =
        new McpToolBridge(
            "mcp-stdio", workspace, registry, executor, trace, mapper, Clock.systemUTC());
    new McpStdioServer(
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
            new PrintWriter(System.out, true, StandardCharsets.UTF_8),
            bridge,
            mapper)
        .serve();
  }
}

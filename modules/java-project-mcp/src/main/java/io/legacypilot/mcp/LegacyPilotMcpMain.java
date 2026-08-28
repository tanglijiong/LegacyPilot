package io.legacypilot.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.observability.FileTraceSink;
import io.legacypilot.observability.SensitiveDataRedactor;
import io.legacypilot.runtime.CapabilityService;
import io.legacypilot.runtime.FileActionJournal;
import io.legacypilot.runtime.FileCapabilityGrantStore;
import io.legacypilot.runtime.FileRunLeaseStore;
import io.legacypilot.sandbox.DockerSandbox;
import io.legacypilot.sandbox.SandboxLimits;
import io.legacypilot.tool.filesystem.ApplyPatchTool;
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
    var mapper = new ObjectMapper().findAndRegisterModules();
    var sandbox = DockerSandbox.secureMavenDefaults();
    var limits = SandboxLimits.safeDefaults();
    var cache = Path.of(System.getProperty("user.home"), ".m2", "repository");
    var registry =
        new ToolRegistry(
            List.of(
                new ReadFileTool(),
                new SearchCodeTool(),
                SearchCodeTool.findReferences(),
                new ApplyPatchTool(List.of("src/**", "pom.xml")),
                new GitDiffTool(),
                MavenTool.compile(sandbox, cache, Set.of(), Set.of(), limits),
                MavenTool.tests(sandbox, cache, Set.of(), Set.of(), limits)));
    var executor = new ToolExecutor(registry, new DefaultExecutionPolicy(), mapper);
    var configuredRoot = System.getenv("LEGACY_PILOT_AGENT_STATE_ROOT");
    var stateRoot =
        configuredRoot == null || configuredRoot.isBlank()
            ? Path.of(System.getProperty("user.dir"), ".legacy-pilot", "agent")
            : Path.of(configuredRoot);
    var trace =
        new FileTraceSink(stateRoot.resolve("traces"), mapper, new SensitiveDataRedactor(8_192));
    var journal = new FileActionJournal(stateRoot.resolve("actions"), mapper);
    var leases = new FileRunLeaseStore(stateRoot.resolve("leases"), mapper);
    var capabilities =
        new CapabilityService(
            new FileCapabilityGrantStore(stateRoot.resolve("capabilities.json"), mapper),
            trace,
            Clock.systemUTC());
    var bridge =
        new McpToolBridge(
            "mcp-stdio",
            workspace,
            registry,
            executor,
            trace,
            mapper,
            Clock.systemUTC(),
            capabilities,
            journal,
            leases);
    new McpStdioServer(
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
            new PrintWriter(System.out, true, StandardCharsets.UTF_8),
            bridge,
            mapper)
        .serve();
  }
}

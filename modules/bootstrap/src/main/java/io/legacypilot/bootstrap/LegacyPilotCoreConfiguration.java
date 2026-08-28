package io.legacypilot.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.analysis.java.JavaProjectIndexer;
import io.legacypilot.application.port.IdGenerator;
import io.legacypilot.application.port.ProjectRepository;
import io.legacypilot.application.port.TaskRepository;
import io.legacypilot.application.port.TaskRunRepository;
import io.legacypilot.application.port.WorkspaceService;
import io.legacypilot.application.service.CancelRunUseCase;
import io.legacypilot.application.service.CreateTaskUseCase;
import io.legacypilot.application.service.GetProjectUseCase;
import io.legacypilot.application.service.GetRunStatusUseCase;
import io.legacypilot.application.service.GetTaskUseCase;
import io.legacypilot.application.service.RegisterProjectUseCase;
import io.legacypilot.application.service.StartRunUseCase;
import io.legacypilot.context.ContextBuilder;
import io.legacypilot.context.ContextCompactor;
import io.legacypilot.context.HybridRetriever;
import io.legacypilot.context.Retriever;
import io.legacypilot.context.TaskMemoryStore;
import io.legacypilot.context.TokenEstimator;
import io.legacypilot.model.ModelCostTable;
import io.legacypilot.model.ModelErrorType;
import io.legacypilot.model.ModelException;
import io.legacypilot.model.RawModelClient;
import io.legacypilot.model.StructuredModelGateway;
import io.legacypilot.model.springai.SpringAiRawModelClient;
import io.legacypilot.observability.AgentMetrics;
import io.legacypilot.observability.FileReportStore;
import io.legacypilot.observability.FileTraceSink;
import io.legacypilot.observability.ReportStore;
import io.legacypilot.observability.SensitiveDataRedactor;
import io.legacypilot.observability.TraceSink;
import io.legacypilot.persistence.JdbcProjectRepository;
import io.legacypilot.persistence.JdbcTaskRepository;
import io.legacypilot.persistence.JdbcTaskRunRepository;
import io.legacypilot.runtime.ActionJournal;
import io.legacypilot.runtime.AgentPlanner;
import io.legacypilot.runtime.AgentRunRequestStore;
import io.legacypilot.runtime.AgentRuntime;
import io.legacypilot.runtime.ApprovalStore;
import io.legacypilot.runtime.CheckpointStore;
import io.legacypilot.runtime.FileActionJournal;
import io.legacypilot.runtime.FileAgentRunRequestStore;
import io.legacypilot.runtime.FileApprovalStore;
import io.legacypilot.runtime.FileCheckpointStore;
import io.legacypilot.runtime.FileRunLeaseStore;
import io.legacypilot.runtime.FileTaskMemoryStore;
import io.legacypilot.runtime.RecoveryCoordinator;
import io.legacypilot.runtime.RunLeaseStore;
import io.legacypilot.sandbox.DockerSandbox;
import io.legacypilot.sandbox.SandboxExecutor;
import io.legacypilot.sandbox.SandboxLimits;
import io.legacypilot.tool.filesystem.ApplyPatchTool;
import io.legacypilot.tool.filesystem.CreatePatchTool;
import io.legacypilot.tool.filesystem.ReadFileTool;
import io.legacypilot.tool.filesystem.SearchCodeTool;
import io.legacypilot.tool.git.GitDiffTool;
import io.legacypilot.tool.maven.MavenTool;
import io.legacypilot.tool.spi.AgentTool;
import io.legacypilot.tool.spi.DefaultExecutionPolicy;
import io.legacypilot.tool.spi.ToolExecutor;
import io.legacypilot.tool.spi.ToolRegistry;
import io.legacypilot.verification.DiffPolicyCheck;
import io.legacypilot.verification.ToolVerificationCheck;
import io.legacypilot.verification.VerificationPipeline;
import io.legacypilot.verification.WorkspaceIntegrityCheck;
import io.legacypilot.workspace.GitWorkspaceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
public class LegacyPilotCoreConfiguration {

  @Bean
  Clock legacyPilotClock() {
    return Clock.systemUTC();
  }

  @Bean
  IdGenerator legacyPilotIdGenerator() {
    return prefix -> prefix + "-" + UUID.randomUUID();
  }

  @Bean
  JdbcClient legacyPilotJdbcClient(DataSource dataSource) {
    return JdbcClient.create(dataSource);
  }

  @Bean
  ProjectRepository projectRepository(JdbcClient jdbc) {
    return new JdbcProjectRepository(jdbc);
  }

  @Bean
  TaskRepository taskRepository(JdbcClient jdbc) {
    return new JdbcTaskRepository(jdbc);
  }

  @Bean
  TaskRunRepository taskRunRepository(JdbcClient jdbc) {
    return new JdbcTaskRunRepository(jdbc);
  }

  @Bean
  WorkspaceService workspaceService(
      @Value("${legacy-pilot.workspace.root:${user.dir}/.legacy-pilot}") Path root,
      @Value("${legacy-pilot.workspace.command-timeout:30s}") Duration timeout,
      @Value("${legacy-pilot.workspace.max-output-characters:65536}") int maxOutputCharacters) {
    return new GitWorkspaceService(root, timeout, maxOutputCharacters);
  }

  @Bean
  RegisterProjectUseCase registerProjectUseCase(
      ProjectRepository projects, WorkspaceService workspaces, IdGenerator ids, Clock clock) {
    return new RegisterProjectUseCase(projects, workspaces, ids, clock);
  }

  @Bean
  GetProjectUseCase getProjectUseCase(ProjectRepository projects) {
    return new GetProjectUseCase(projects);
  }

  @Bean
  CreateTaskUseCase createTaskUseCase(
      ProjectRepository projects, TaskRepository tasks, IdGenerator ids, Clock clock) {
    return new CreateTaskUseCase(projects, tasks, ids, clock);
  }

  @Bean
  GetTaskUseCase getTaskUseCase(TaskRepository tasks) {
    return new GetTaskUseCase(tasks);
  }

  @Bean
  StartRunUseCase startRunUseCase(
      ProjectRepository projects,
      TaskRepository tasks,
      TaskRunRepository runs,
      WorkspaceService workspaces,
      IdGenerator ids,
      Clock clock) {
    return new StartRunUseCase(projects, tasks, runs, workspaces, ids, clock);
  }

  @Bean
  GetRunStatusUseCase getRunStatusUseCase(TaskRunRepository runs) {
    return new GetRunStatusUseCase(runs);
  }

  @Bean
  CancelRunUseCase cancelRunUseCase(
      ProjectRepository projects,
      TaskRepository tasks,
      TaskRunRepository runs,
      WorkspaceService workspaces,
      Clock clock) {
    return new CancelRunUseCase(projects, tasks, runs, workspaces, clock);
  }

  @Bean
  SandboxExecutor sandboxExecutor() {
    return DockerSandbox.secureMavenDefaults();
  }

  @Bean
  JavaProjectIndexer javaProjectIndexer() {
    return new JavaProjectIndexer();
  }

  @Bean
  Retriever codeRetriever() {
    return HybridRetriever.defaults();
  }

  @Bean
  ContextBuilder contextBuilder(Retriever retriever) {
    return new ContextBuilder(retriever, TokenEstimator.conservative());
  }

  @Bean
  ReadFileTool readFileTool() {
    return new ReadFileTool();
  }

  @Bean
  SearchCodeTool searchCodeTool() {
    return new SearchCodeTool();
  }

  @Bean
  AgentTool findReferencesTool() {
    return SearchCodeTool.findReferences();
  }

  @Bean
  CreatePatchTool createPatchTool() {
    return new CreatePatchTool();
  }

  @Bean
  ApplyPatchTool applyPatchTool(
      @Value("${legacy-pilot.tools.writable-globs:src/**,pom.xml}") List<String> writableGlobs) {
    return new ApplyPatchTool(writableGlobs);
  }

  @Bean
  GitDiffTool gitDiffTool() {
    return new GitDiffTool();
  }

  @Bean
  SandboxLimits sandboxLimits() {
    return SandboxLimits.safeDefaults();
  }

  @Bean
  MavenTool compileProjectTool(
      SandboxExecutor sandbox,
      SandboxLimits limits,
      @Value("${legacy-pilot.maven.cache:${user.home}/.m2/repository}") Path cache) {
    return MavenTool.compile(sandbox, cache, Set.of(), mavenProperties(), limits);
  }

  @Bean
  MavenTool runTestsTool(
      SandboxExecutor sandbox,
      SandboxLimits limits,
      @Value("${legacy-pilot.maven.cache:${user.home}/.m2/repository}") Path cache) {
    return MavenTool.tests(sandbox, cache, Set.of(), mavenProperties(), limits);
  }

  @Bean
  MavenTool runTestClassTool(
      SandboxExecutor sandbox,
      SandboxLimits limits,
      @Value("${legacy-pilot.maven.cache:${user.home}/.m2/repository}") Path cache) {
    return MavenTool.testClass(sandbox, cache, Set.of(), mavenProperties(), limits);
  }

  @Bean
  MavenTool staticAnalysisTool(
      SandboxExecutor sandbox,
      SandboxLimits limits,
      @Value("${legacy-pilot.maven.cache:${user.home}/.m2/repository}") Path cache) {
    return MavenTool.staticAnalysis(sandbox, cache, Set.of(), mavenProperties(), limits);
  }

  @Bean
  ToolRegistry toolRegistry(List<AgentTool> tools) {
    return new ToolRegistry(tools);
  }

  @Bean
  ToolExecutor toolExecutor(ToolRegistry registry, ObjectMapper mapper) {
    return new ToolExecutor(registry, new DefaultExecutionPolicy(), mapper);
  }

  @Bean
  RawModelClient rawModelClient(ObjectProvider<ChatModel> chatModel) {
    var provider = chatModel.getIfAvailable();
    if (provider == null) {
      return request -> {
        throw new ModelException(
            ModelErrorType.PROVIDER_UNAVAILABLE, "No Spring AI ChatModel is configured", false);
      };
    }
    return new SpringAiRawModelClient(provider, new ModelCostTable(java.util.Map.of()));
  }

  @Bean
  StructuredModelGateway modelGateway(RawModelClient client, ObjectMapper mapper) {
    return new StructuredModelGateway(client, mapper, 2);
  }

  @Bean
  AgentPlanner agentPlanner(StructuredModelGateway models, ObjectMapper mapper) {
    return new AgentPlanner(models, mapper);
  }

  @Bean
  CheckpointStore checkpointStore(
      @Value("${legacy-pilot.agent.state-root:${user.dir}/.legacy-pilot/agent}") Path stateRoot,
      ObjectMapper mapper) {
    return new FileCheckpointStore(stateRoot.resolve("checkpoints"), mapper);
  }

  @Bean
  ApprovalStore approvalStore(
      @Value("${legacy-pilot.agent.state-root:${user.dir}/.legacy-pilot/agent}") Path stateRoot,
      ObjectMapper mapper) {
    return new FileApprovalStore(stateRoot.resolve("approvals.json"), mapper);
  }

  @Bean
  AgentRunRequestStore agentRunRequestStore(
      @Value("${legacy-pilot.agent.state-root:${user.dir}/.legacy-pilot/agent}") Path stateRoot,
      ObjectMapper mapper) {
    return new FileAgentRunRequestStore(stateRoot.resolve("requests"), mapper);
  }

  @Bean
  TraceSink traceSink(
      @Value("${legacy-pilot.agent.state-root:${user.dir}/.legacy-pilot/agent}") Path stateRoot,
      ObjectMapper mapper) {
    return new FileTraceSink(stateRoot.resolve("traces"), mapper, new SensitiveDataRedactor(8_192));
  }

  @Bean
  ActionJournal actionJournal(
      @Value("${legacy-pilot.agent.state-root:${user.dir}/.legacy-pilot/agent}") Path stateRoot,
      ObjectMapper mapper) {
    return new FileActionJournal(stateRoot.resolve("actions"), mapper);
  }

  @Bean
  RunLeaseStore runLeaseStore(
      @Value("${legacy-pilot.agent.state-root:${user.dir}/.legacy-pilot/agent}") Path stateRoot,
      ObjectMapper mapper) {
    return new FileRunLeaseStore(stateRoot.resolve("leases"), mapper);
  }

  @Bean
  TaskMemoryStore taskMemoryStore(
      @Value("${legacy-pilot.agent.state-root:${user.dir}/.legacy-pilot/agent}") Path stateRoot,
      ObjectMapper mapper,
      @Value("${legacy-pilot.agent.memory.maximum-entries:5000}") int maximumEntries) {
    return new FileTaskMemoryStore(stateRoot.resolve("memory"), mapper, maximumEntries);
  }

  @Bean
  ContextCompactor contextCompactor() {
    return new ContextCompactor(TokenEstimator.conservative());
  }

  @Bean
  ReportStore reportStore(
      @Value("${legacy-pilot.agent.state-root:${user.dir}/.legacy-pilot/agent}") Path stateRoot,
      ObjectMapper mapper) {
    return new FileReportStore(stateRoot.resolve("reports"), mapper);
  }

  @Bean
  AgentMetrics agentMetrics() {
    return new AgentMetrics(new SimpleMeterRegistry());
  }

  @Bean
  VerificationPipeline verificationPipeline(
      ObjectMapper mapper,
      @Value("${legacy-pilot.verification.maximum-changed-lines:2000}") int maximumChangedLines,
      @Value("${legacy-pilot.verification.protected-globs:.git/**,.github/**}")
          List<String> protectedGlobs) {
    var empty = mapper.createObjectNode();
    return new VerificationPipeline(
        List.of(
            new WorkspaceIntegrityCheck(),
            new DiffPolicyCheck(maximumChangedLines, protectedGlobs),
            new ToolVerificationCheck("compile", "compile_project", empty, true, true),
            new ToolVerificationCheck("tests", "run_tests", empty, true, true),
            new ToolVerificationCheck("static-analysis", "static_analysis", empty, true, true)));
  }

  @Bean
  AgentRuntime agentRuntime(
      AgentPlanner planner,
      ContextBuilder contexts,
      ToolExecutor tools,
      VerificationPipeline verification,
      CheckpointStore checkpoints,
      AgentRunRequestStore requests,
      ApprovalStore approvals,
      TraceSink trace,
      ReportStore reports,
      AgentMetrics metrics,
      ObjectMapper mapper,
      Clock clock,
      ActionJournal journal,
      RunLeaseStore leases,
      TaskMemoryStore memories,
      ContextCompactor compactor,
      @Value("${legacy-pilot.agent.owner:}") String owner,
      @Value("${legacy-pilot.agent.lease-ttl:2m}") Duration leaseTtl) {
    return new AgentRuntime(
        planner,
        contexts,
        tools,
        verification,
        checkpoints,
        requests,
        approvals,
        trace,
        reports,
        metrics,
        mapper,
        clock,
        journal,
        leases,
        memories,
        compactor,
        owner.isBlank() ? "runtime-" + UUID.randomUUID() : owner,
        leaseTtl);
  }

  @Bean
  RecoveryCoordinator recoveryCoordinator(
      @Value("${legacy-pilot.agent.state-root:${user.dir}/.legacy-pilot/agent}") Path stateRoot,
      ObjectMapper mapper,
      AgentRuntime runtime) {
    return new RecoveryCoordinator(
        new FileCheckpointStore(stateRoot.resolve("checkpoints"), mapper), runtime);
  }

  private static Set<String> mavenProperties() {
    return Set.of("skipTests", "maven.test.failure.ignore");
  }
}

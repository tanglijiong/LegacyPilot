package io.legacypilot.server;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.legacypilot.observability.TraceSink;
import io.legacypilot.server.security.RunSecurityContextStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class LifecycleApiTest {

  @TempDir static Path temporaryDirectory;
  private static Path sourceRepository;

  @Autowired MockMvc mvc;
  @Autowired RunSecurityContextStore runSecurityContexts;
  @Autowired TraceSink trace;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url", () -> "jdbc:h2:mem:server-api;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
    registry.add(
        "legacy-pilot.workspace.root", () -> temporaryDirectory.resolve("managed").toString());
    registry.add(
        "legacy-pilot.agent.state-root", () -> temporaryDirectory.resolve("agent").toString());
    registry.add("legacy-pilot.security.mode", () -> "test");
  }

  @BeforeAll
  static void createSourceRepository() throws IOException {
    sourceRepository = temporaryDirectory.resolve("source");
    Files.createDirectories(sourceRepository);
    git("init", "--initial-branch=main");
    git("config", "user.name", "LegacyPilot Test");
    git("config", "user.email", "test@legacypilot.invalid");
    Files.writeString(sourceRepository.resolve("README.md"), "alpha\nfixture\ngamma\n");
    Files.write(sourceRepository.resolve("binary.dat"), new byte[] {1, 0, 2});
    Files.createSymbolicLink(sourceRepository.resolve("external-link"), Path.of("/etc/passwd"));
    git("add", "README.md", "binary.dat", "external-link");
    git("commit", "-m", "fixture");
  }

  @Test
  void exposesDurableLifecycleThroughHttp() throws Exception {
    mvc.perform(
            post("/api/v1/projects/preflight")
                .with(user("developer-a").roles("DEVELOPER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\"" + escape(sourceRepository.toString()) + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resolvedRevision").isNotEmpty())
        .andExpect(jsonPath("$.compilation").value("NOT_RUN"))
        .andExpect(jsonPath("$.technologies[0]").value("OTHER"))
        .andExpect(jsonPath("$.capabilities[0]").value("SOURCE_BROWSING"))
        .andExpect(jsonPath("$.warnings[0]").value("JAVA_SEMANTIC_ANALYSIS_UNAVAILABLE"));

    mvc.perform(
            multipart("/api/v1/projects/preflight-directory")
                .file(
                    new MockMultipartFile(
                        "files", "app.py", "text/plain", "print('offline')\n".getBytes()))
                .param("directoryName", "offline-project")
                .param("paths", "src/app.py")
                .with(user("developer-a").roles("DEVELOPER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value(org.hamcrest.Matchers.startsWith("directory://")))
        .andExpect(jsonPath("$.sourceKind").value("LOCAL_DIRECTORY"))
        .andExpect(jsonPath("$.technologies[0]").value("PYTHON"))
        .andExpect(jsonPath("$.warnings[0]").value("DIRECTORY_SNAPSHOT_CREATED"));

    var projectBody =
        mvc.perform(
                post("/api/v1/projects")
                    .with(user("developer-a").roles("DEVELOPER"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"source\":\"" + escape(sourceRepository.toString()) + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.baseRevision").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var projectId = jsonValue(projectBody, "id");
    var revision = jsonValue(projectBody, "baseRevision");

    mvc.perform(get("/api/v1/projects/{id}", projectId).with(user("auditor-a").roles("AUDITOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(projectId));
    mvc.perform(get("/api/v1/projects").with(user("auditor-a").roles("AUDITOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(projectId));

    mvc.perform(
            get("/api/v1/projects/{id}/source", projectId)
                .param("revision", revision)
                .param("path", "README.md")
                .param("startLine", "2")
                .param("endLine", "2")
                .param("contextLines", "1")
                .with(user("auditor-a").roles("AUDITOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revision").value(revision))
        .andExpect(jsonPath("$.path").value("README.md"))
        .andExpect(jsonPath("$.startLine").value(1))
        .andExpect(jsonPath("$.endLine").value(3))
        .andExpect(jsonPath("$.content").value("alpha\nfixture\ngamma"))
        .andExpect(jsonPath("$.contentSha256").isNotEmpty())
        .andExpect(jsonPath("$.requestedRangeSha256").isNotEmpty())
        .andExpect(jsonPath("$.snippetSha256").isNotEmpty());
    mvc.perform(
            get("/api/v1/projects/{id}/source", projectId)
                .param("revision", revision)
                .param("path", "../README.md")
                .with(user("auditor-a").roles("AUDITOR")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("SOURCE_INVALID_PATH"));
    mvc.perform(
            get("/api/v1/projects/{id}/source", projectId)
                .param("revision", "f".repeat(40))
                .param("path", "README.md")
                .with(user("auditor-a").roles("AUDITOR")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SOURCE_REVISION_MISMATCH"));
    mvc.perform(
            get("/api/v1/projects/{id}/source", projectId)
                .param("revision", revision)
                .param("path", "external-link")
                .with(user("auditor-a").roles("AUDITOR")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("SOURCE_SYMBOLIC_LINK"));
    mvc.perform(
            get("/api/v1/projects/{id}/source", projectId)
                .param("revision", revision)
                .param("path", "binary.dat")
                .with(user("auditor-a").roles("AUDITOR")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("SOURCE_BINARY_FILE"));

    var taskBody =
        mvc.perform(
                post("/api/v1/projects/{id}/tasks", projectId)
                    .with(user("developer-a").roles("DEVELOPER"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"mode\":\"ANALYZE\",\"requirement\":\"safe change\","
                            + "\"criteria\":[\"tests pass\"]}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.mode").value("ANALYZE"))
            .andExpect(jsonPath("$.criteria[0]").value("tests pass"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var taskId = jsonValue(taskBody, "id");

    mvc.perform(get("/api/v1/tasks/{id}", taskId).with(user("auditor-a").roles("AUDITOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.projectId").value(projectId))
        .andExpect(jsonPath("$.mode").value("ANALYZE"));

    var runBody =
        mvc.perform(
                post("/api/v1/tasks/{id}/runs", taskId)
                    .with(user("developer-a").roles("DEVELOPER")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("WORKSPACE_READY"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var runId = jsonValue(runBody, "id");

    mvc.perform(
            get("/api/v1/projects/{id}/tasks", projectId).with(user("auditor-a").roles("AUDITOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(taskId))
        .andExpect(jsonPath("$[0].latestRun.id").value(runId))
        .andExpect(jsonPath("$[0].latestRun.status").value("WORKSPACE_READY"));

    mvc.perform(
            post("/api/v1/projects/{id}/tasks", projectId)
                .with(user("developer-a").roles("DEVELOPER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"mode\":\"EXPLAIN\",\"requirement\":\"follow up\","
                        + "\"criteria\":[],\"conversationId\":\""
                        + taskId
                        + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.conversationId").value(taskId));

    runSecurityContexts.recordInitiator(runId, "developer-a");
    assertThrows(
        IllegalStateException.class,
        () -> runSecurityContexts.requireIndependentApprover(runId, "developer-a"));
    runSecurityContexts.requireIndependentApprover(runId, "approver-a");

    mvc.perform(get("/api/v1/runs/{id}", runId).with(user("auditor-a").roles("AUDITOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workspaceId").value(runId))
        .andExpect(jsonPath("$.history.length()").value(2));
    mvc.perform(
            post("/api/v1/runs/{id}/cancel", runId)
                .header("If-Match", "2")
                .with(user("developer-a").roles("DEVELOPER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));

    trace.record(
        runId,
        "command.accepted",
        Instant.EPOCH,
        Map.of("operation", "EXECUTE", "correlationId", "correlation-1"));
    trace.record(
        runId, "run.completed", Instant.EPOCH.plusSeconds(1), Map.of("status", "CANCELLED"));
    var stream =
        mvc.perform(
                get("/api/v1/runs/{id}/events", runId)
                    .header("Last-Event-ID", "1")
                    .with(user("auditor-a").roles("AUDITOR")))
            .andExpect(request().asyncStarted())
            .andReturn();
    mvc.perform(asyncDispatch(stream))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
        .andExpect(content().string(not(containsString("id:1"))))
        .andExpect(content().string(containsString("id:2")))
        .andExpect(content().string(containsString("\"status\":\"CANCELLED\"")));

    mvc.perform(
            delete("/api/v1/projects/{id}", projectId).with(user("developer-a").roles("DEVELOPER")))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/projects").with(user("auditor-a").roles("AUDITOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
    mvc.perform(get("/api/v1/projects/{id}", projectId).with(user("auditor-a").roles("AUDITOR")))
        .andExpect(status().isNotFound());
  }

  @Test
  void returnsProblemDetailsForBadInputAndMissingResources() throws Exception {
    mvc.perform(get("/api/v1/projects/missing").with(user("auditor-a").roles("AUDITOR")))
        .andExpect(status().isNotFound())
        .andExpect(header().exists("X-Correlation-ID"))
        .andExpect(jsonPath("$.title").value("Not Found"))
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        .andExpect(jsonPath("$.correlationId").isNotEmpty());
    var correlationId = "123e4567-e89b-12d3-a456-426614174000";
    mvc.perform(
            get("/api/v1/projects/missing")
                .with(user("auditor-a").roles("AUDITOR"))
                .header("X-Correlation-ID", correlationId))
        .andExpect(status().isNotFound())
        .andExpect(header().string("X-Correlation-ID", correlationId))
        .andExpect(jsonPath("$.correlationId").value(correlationId));
    mvc.perform(
            post("/api/v1/projects")
                .with(user("developer-a").roles("DEVELOPER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\" \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Bad Request"))
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  private static String jsonValue(String json, String field) {
    var prefix = "\"" + field + "\":\"";
    var start = json.indexOf(prefix) + prefix.length();
    return json.substring(start, json.indexOf('"', start));
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static void git(String... arguments) {
    var command = new java.util.ArrayList<String>();
    command.add("git");
    command.addAll(List.of(arguments));
    try {
      var process = new ProcessBuilder(command).directory(sourceRepository.toFile()).start();
      var error =
          new String(
              process.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      if (process.waitFor() != 0) {
        throw new IllegalStateException(error);
      }
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }
}

package io.legacypilot.server;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class LifecycleApiTest {

  @TempDir static Path temporaryDirectory;
  private static Path sourceRepository;

  @Autowired MockMvc mvc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url", () -> "jdbc:h2:mem:server-api;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
    registry.add(
        "legacy-pilot.workspace.root", () -> temporaryDirectory.resolve("managed").toString());
  }

  @BeforeAll
  static void createSourceRepository() throws IOException {
    sourceRepository = temporaryDirectory.resolve("source");
    Files.createDirectories(sourceRepository);
    git("init", "--initial-branch=main");
    git("config", "user.name", "LegacyPilot Test");
    git("config", "user.email", "test@legacypilot.invalid");
    Files.writeString(sourceRepository.resolve("README.md"), "fixture\n");
    git("add", "README.md");
    git("commit", "-m", "fixture");
  }

  @Test
  void exposesDurableLifecycleThroughHttp() throws Exception {
    var projectBody =
        mvc.perform(
                post("/api/v1/projects")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"source\":\"" + escape(sourceRepository.toString()) + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.baseRevision").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var projectId = jsonValue(projectBody, "id");

    mvc.perform(get("/api/v1/projects/{id}", projectId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(projectId));

    var taskBody =
        mvc.perform(
                post("/api/v1/projects/{id}/tasks", projectId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"requirement\":\"safe change\",\"criteria\":[\"tests pass\"]}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.criteria[0]").value("tests pass"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var taskId = jsonValue(taskBody, "id");

    mvc.perform(get("/api/v1/tasks/{id}", taskId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.projectId").value(projectId));

    var runBody =
        mvc.perform(post("/api/v1/tasks/{id}/runs", taskId))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("WORKSPACE_READY"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var runId = jsonValue(runBody, "id");

    mvc.perform(get("/api/v1/runs/{id}", runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.workspaceId").value(runId))
        .andExpect(jsonPath("$.history.length()").value(2));
    mvc.perform(post("/api/v1/runs/{id}/cancel", runId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
  }

  @Test
  void returnsProblemDetailsForBadInputAndMissingResources() throws Exception {
    mvc.perform(get("/api/v1/projects/missing"))
        .andExpect(status().isNotFound())
        .andExpect(header().exists("X-Correlation-ID"))
        .andExpect(jsonPath("$.title").value("Not Found"));
    var correlationId = "123e4567-e89b-12d3-a456-426614174000";
    mvc.perform(get("/api/v1/projects/missing").header("X-Correlation-ID", correlationId))
        .andExpect(status().isNotFound())
        .andExpect(header().string("X-Correlation-ID", correlationId));
    mvc.perform(
            post("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\" \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Bad Request"));
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

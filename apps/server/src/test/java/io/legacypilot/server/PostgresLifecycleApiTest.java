package io.legacypilot.server;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.legacypilot.server.security.RunSecurityContextStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PostgresLifecycleApiTest {

  private static final DockerImageName POSTGRES_IMAGE =
      DockerImageName.parse(
              System.getenv()
                  .getOrDefault("LEGACY_PILOT_POSTGRES_TEST_IMAGE", "postgres:16-alpine"))
          .asCompatibleSubstituteFor("postgres");

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(POSTGRES_IMAGE)
          .withDatabaseName("legacypilot")
          .withUsername("legacypilot_test")
          .withPassword(UUID.randomUUID().toString());

  @TempDir static Path temporaryDirectory;
  private static Path sourceRepository;

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired RunSecurityContextStore runSecurityContexts;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.flyway.locations", () -> "classpath:db/postgresql");
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add(
        "legacy-pilot.workspace.root", () -> temporaryDirectory.resolve("managed").toString());
    registry.add("legacy-pilot.security.mode", () -> "test");
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
  void migratesAndPersistsLifecycleOnPostgres() throws Exception {
    var projectBody =
        mvc.perform(
                post("/api/v1/projects")
                    .with(user("developer-postgres").roles("DEVELOPER"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"source\":\"" + escape(sourceRepository.toString()) + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var projectId = jsonValue(projectBody, "id");

    var taskBody =
        mvc.perform(
                post("/api/v1/projects/{id}/tasks", projectId)
                    .with(user("developer-postgres").roles("DEVELOPER"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"requirement\":\"safe change\",\"criteria\":[\"tests pass\"]}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var taskId = jsonValue(taskBody, "id");

    var runBody =
        mvc.perform(
                post("/api/v1/tasks/{id}/runs", taskId)
                    .with(user("developer-postgres").roles("DEVELOPER")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("WORKSPACE_READY"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var runId = jsonValue(runBody, "id");

    mvc.perform(get("/api/v1/runs/{id}", runId).with(user("auditor-postgres").roles("AUDITOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.taskId").value(taskId));

    runSecurityContexts.recordInitiator(runId, "developer-postgres");

    var appliedMigrations =
        jdbc.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE success")
            .query(Integer.class)
            .single();
    var securityContexts =
        jdbc.sql("SELECT COUNT(*) FROM run_security_context WHERE run_id = :runId")
            .param("runId", runId)
            .query(Integer.class)
            .single();

    org.junit.jupiter.api.Assertions.assertEquals(2, appliedMigrations);
    org.junit.jupiter.api.Assertions.assertEquals(1, securityContexts);
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
    var command = new ArrayList<String>();
    command.add("git");
    command.addAll(List.of(arguments));
    try {
      var process = new ProcessBuilder(command).directory(sourceRepository.toFile()).start();
      var error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
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

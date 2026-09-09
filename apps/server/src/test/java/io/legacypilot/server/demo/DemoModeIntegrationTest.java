package io.legacypilot.server.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class DemoModeIntegrationTest {
  private static final Path ROOT = createFixture();

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url", () -> "jdbc:h2:mem:server-demo;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
    registry.add("legacy-pilot.demo.source", () -> ROOT.resolve("source").toString());
    registry.add("legacy-pilot.workspace.root", () -> ROOT.resolve("managed").toString());
    registry.add("legacy-pilot.agent.state-root", () -> ROOT.resolve("agent").toString());
  }

  @Test
  void bootstrapsProjectAndIssuesProcessLocalSeparatedIdentities() throws Exception {
    var sessionResponse =
        mvc.perform(get("/api/v1/demo/session"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.mode").value("demo"))
            .andExpect(jsonPath("$.developer.subject").value("demo-developer"))
            .andExpect(jsonPath("$.developer.roles[0]").value("DEVELOPER"))
            .andExpect(jsonPath("$.approver.subject").value("demo-approver"))
            .andExpect(jsonPath("$.approver.roles[0]").value("APPROVER"))
            .andExpect(jsonPath("$.project.revision").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var session = mapper.readTree(sessionResponse);
    var developerToken = session.path("developer").path("token").asText();
    var approverToken = session.path("approver").path("token").asText();

    mvc.perform(get("/api/v1/projects").header("Authorization", "Bearer " + developerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].source").value(ROOT.resolve("source").toRealPath().toString()));
    mvc.perform(
            post("/api/v1/projects")
                .header("Authorization", "Bearer " + approverToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\"/tmp/not-allowed\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void refusesToIssueOrAcceptDemoCredentialsOffLoopback() throws Exception {
    var token =
        mapper
            .readTree(
                mvc.perform(get("/api/v1/demo/session"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("developer")
            .path("token")
            .asText();

    mvc.perform(get("/api/v1/demo/session").with(request -> remote(request, "192.0.2.10")))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/projects")
                .with(request -> remote(request, "192.0.2.10"))
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  private static org.springframework.mock.web.MockHttpServletRequest remote(
      org.springframework.mock.web.MockHttpServletRequest request, String address) {
    request.setRemoteAddr(address);
    return request;
  }

  private static Path createFixture() {
    try {
      var root = Files.createTempDirectory("legacy-pilot-demo-test-");
      var source = Files.createDirectories(root.resolve("source"));
      Files.writeString(source.resolve("README.md"), "demo\n", StandardCharsets.UTF_8);
      git(source, "init", "--initial-branch=main");
      git(source, "config", "user.name", "LegacyPilot Test");
      git(source, "config", "user.email", "test@legacypilot.invalid");
      git(source, "add", "README.md");
      git(source, "commit", "-m", "demo fixture");
      return root;
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static void git(Path source, String... arguments) {
    var command = new java.util.ArrayList<String>();
    command.add("git");
    command.addAll(List.of(arguments));
    try {
      var process = new ProcessBuilder(command).directory(source.toFile()).start();
      if (process.waitFor() != 0) {
        throw new IllegalStateException(
            new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
      }
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }
}

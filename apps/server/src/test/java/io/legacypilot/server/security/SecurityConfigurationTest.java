package io.legacypilot.server.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigurationTest {
  @Autowired MockMvc mvc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:h2:mem:server-security;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
    registry.add("legacy-pilot.security.mode", () -> "test");
    registry.add("springdoc.api-docs.enabled", () -> "true");
  }

  @Test
  void rejectsAnonymousRequestsAndEnforcesRoles() throws Exception {
    mvc.perform(get("/api/v1/projects/missing"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists("X-Correlation-ID"))
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
        .andExpect(jsonPath("$.correlationId").isNotEmpty());
    mvc.perform(
            post("/api/v1/projects")
                .with(user("auditor-a").roles("AUDITOR"))
                .contentType("application/json")
                .content("{\"source\":\"/tmp/source\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/projects/missing").with(user("auditor-a").roles("AUDITOR")))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/capabilities")
                .with(user("developer-a").roles("DEVELOPER"))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void exposesTheUnifiedExecutionContractOnlyToAdministrators() throws Exception {
    mvc.perform(get("/v3/api-docs").with(user("auditor-a").roles("AUDITOR")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    mvc.perform(get("/v3/api-docs").with(user("admin-a").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/v1/runs/{runId}/execute']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/runs/{runId}/approvals']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/runs/{runId}/resume']").exists())
        .andExpect(jsonPath("$.paths['/api/v1/runs/{runId}/report']").exists());
  }
}

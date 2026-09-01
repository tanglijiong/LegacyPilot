package io.legacypilot.server.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(OidcSecurityConfigurationTest.JwtTestConfiguration.class)
class OidcSecurityConfigurationTest {
  @Autowired MockMvc mvc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url", () -> "jdbc:h2:mem:server-oidc;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
    registry.add("legacy-pilot.security.mode", () -> "oidc");
  }

  @Test
  void mapsTheOidcRolesClaimAndUsesTheJwtSubject() throws Exception {
    mvc.perform(post("/api/v1/runs/missing/cancel").header("Authorization", "Bearer auditor-token"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    mvc.perform(
            post("/api/v1/runs/missing/cancel").header("Authorization", "Bearer developer-token"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    mvc.perform(get("/api/v1/projects/missing").header("Authorization", "Bearer auditor-token"))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/capabilities")
                .header("Authorization", "Bearer admin-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "subject":"forged-client-subject",
                      "sessionId":"session-1",
                      "runId":"run-1",
                      "tool":"apply_patch",
                      "workspace":"/tmp",
                      "actionDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                      "planDigest":"",
                      "expiresAt":"2030-01-01T00:00:00Z",
                      "maximumUses":1
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.capability.subject").value("admin-token-subject"));
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class JwtTestConfiguration {
    @Bean
    JwtDecoder jwtDecoder() {
      return token -> jwt(token, role(token));
    }

    private static String role(String token) {
      if (token.startsWith("developer")) {
        return "DEVELOPER";
      }
      return token.startsWith("admin") ? "ADMIN" : "AUDITOR";
    }

    private static Jwt jwt(String token, String role) {
      var now = Instant.now();
      return Jwt.withTokenValue(token)
          .header("alg", "RS256")
          .subject(token + "-subject")
          .issuedAt(now.minusSeconds(60))
          .expiresAt(now.plusSeconds(300))
          .claim("roles", List.of(role))
          .build();
    }
  }
}

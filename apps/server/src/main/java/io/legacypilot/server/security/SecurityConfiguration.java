package io.legacypilot.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.server.api.ApiProblems;
import jakarta.servlet.DispatcherType;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfiguration {

  @Bean
  SecurityFilterChain legacyPilotSecurity(
      HttpSecurity http,
      ObjectMapper objectMapper,
      ObjectProvider<DemoAccessService> demoAccess,
      @Value("${legacy-pilot.security.mode:deny-all}") String mode)
      throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(
            sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(
                        (request, response, exception) ->
                            writeProblem(
                                response,
                                objectMapper,
                                HttpStatus.UNAUTHORIZED,
                                "AUTHENTICATION_REQUIRED",
                                "Authentication is required"))
                    .accessDeniedHandler(
                        (request, response, exception) ->
                            writeProblem(
                                response,
                                objectMapper,
                                HttpStatus.FORBIDDEN,
                                "ACCESS_DENIED",
                                "The authenticated identity is not authorized for this operation")))
        .authorizeHttpRequests(
            requests -> {
              requests.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
              requests.requestMatchers(HttpMethod.GET, "/api/v1/demo/session").permitAll();
              requests
                  .requestMatchers(HttpMethod.POST, "/api/v1/demo/faults/**")
                  .hasRole("DEVELOPER");
              requests.requestMatchers(HttpMethod.POST, "/api/v1/capabilities").hasRole("ADMIN");
              requests
                  .requestMatchers(HttpMethod.DELETE, "/api/v1/capabilities/**")
                  .hasRole("ADMIN");
              requests
                  .requestMatchers(HttpMethod.POST, "/api/v1/runs/*/approvals")
                  .hasRole("APPROVER");
              requests
                  .requestMatchers(HttpMethod.POST, "/api/v1/runs/*/apply-to-source")
                  .hasRole("APPROVER");
              requests
                  .requestMatchers(HttpMethod.POST, "/api/v1/agent-runs/*/approvals")
                  .hasRole("APPROVER");
              requests
                  .requestMatchers(HttpMethod.POST, "/api/v1/agent-runs/recovery")
                  .hasRole("ADMIN");
              requests
                  .requestMatchers(HttpMethod.POST, "/api/v1/agent-runs/*/resume")
                  .hasRole("ADMIN");
              requests.requestMatchers(HttpMethod.POST, "/api/v1/agent-runs").hasRole("ADMIN");
              requests.requestMatchers(HttpMethod.POST, "/api/v1/projects/**").hasRole("DEVELOPER");
              requests
                  .requestMatchers(HttpMethod.DELETE, "/api/v1/projects/*")
                  .hasRole("DEVELOPER");
              requests.requestMatchers(HttpMethod.POST, "/api/v1/tasks/**").hasRole("DEVELOPER");
              requests
                  .requestMatchers(HttpMethod.POST, "/api/v1/runs/*/execute")
                  .hasRole("DEVELOPER");
              requests
                  .requestMatchers(HttpMethod.POST, "/api/v1/runs/*/resume")
                  .hasAnyRole("DEVELOPER", "APPROVER");
              requests
                  .requestMatchers(HttpMethod.POST, "/api/v1/runs/*/cancel")
                  .hasRole("DEVELOPER");
              requests
                  .requestMatchers(HttpMethod.POST, "/api/v1/runs/*/retry", "/api/v1/runs/*/replan")
                  .hasRole("DEVELOPER");
              requests
                  .requestMatchers(HttpMethod.GET, "/api/v1/projects/*/source")
                  .hasAnyRole("ADMIN", "DEVELOPER", "AUDITOR");
              requests
                  .requestMatchers(HttpMethod.GET, "/api/**")
                  .hasAnyRole("ADMIN", "DEVELOPER", "APPROVER", "AUDITOR");
              requests.requestMatchers("/docs/**", "/v3/api-docs/**").hasRole("ADMIN");
              requests.anyRequest().denyAll();
            });
    if ("oidc".equals(mode)) {
      var authorities = new JwtGrantedAuthoritiesConverter();
      authorities.setAuthoritiesClaimName("roles");
      authorities.setAuthorityPrefix("ROLE_");
      var converter = new JwtAuthenticationConverter();
      converter.setJwtGrantedAuthoritiesConverter(authorities);
      http.oauth2ResourceServer(
          resourceServer -> resourceServer.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
    } else if ("demo".equals(mode)) {
      var access = demoAccess.getIfAvailable();
      if (access == null) {
        throw new IllegalArgumentException("demo security mode requires the demo Spring profile");
      }
      http.addFilterBefore(
          new DemoAuthenticationFilter(access), UsernamePasswordAuthenticationFilter.class);
    } else if (!"test".equals(mode) && !"deny-all".equals(mode)) {
      throw new IllegalArgumentException(
          "legacy-pilot.security.mode must be deny-all, oidc, or demo");
    }
    return http.build();
  }

  private static void writeProblem(
      jakarta.servlet.http.HttpServletResponse response,
      ObjectMapper objectMapper,
      HttpStatus status,
      String code,
      String detail)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    objectMapper.writeValue(response.getOutputStream(), ApiProblems.create(status, code, detail));
  }
}

package io.legacypilot.server.api;

import io.legacypilot.server.security.RunSecurityContextStore;
import io.legacypilot.server.service.ChangeApplicationService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/runs")
public final class ChangeApplicationController {
  private final ChangeApplicationService applications;
  private final RunSecurityContextStore securityContexts;

  public ChangeApplicationController(
      ChangeApplicationService applications, RunSecurityContextStore securityContexts) {
    this.applications = applications;
    this.securityContexts = securityContexts;
  }

  @PostMapping("/{runId}/apply-to-source")
  ChangeApplicationService.ApplicationResult apply(
      @PathVariable String runId,
      @RequestBody ApplyChangeRequest request,
      Authentication authentication) {
    securityContexts.requireIndependentApprover(runId, authentication.getName());
    return applications.apply(runId, request.targetPath(), authentication.getName());
  }

  public record ApplyChangeRequest(String targetPath) {}
}

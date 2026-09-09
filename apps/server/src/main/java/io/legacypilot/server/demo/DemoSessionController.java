package io.legacypilot.server.demo;

import io.legacypilot.server.security.DemoAccessService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("demo")
@RequestMapping("/api/v1/demo")
public final class DemoSessionController {
  private final DemoAccessService access;
  private final DemoBootstrap bootstrap;

  public DemoSessionController(DemoAccessService access, DemoBootstrap bootstrap) {
    this.access = access;
    this.bootstrap = bootstrap;
  }

  @GetMapping("/session")
  ResponseEntity<DemoSession> session(HttpServletRequest request) {
    if (!access.isLoopback(request)) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.FORBIDDEN,
          "Demo sessions are available only from the local machine");
    }
    var project = bootstrap.project();
    var body =
        new DemoSession(
            "demo",
            new DemoIdentity("demo-developer", access.developerToken(), List.of("DEVELOPER")),
            new DemoIdentity("demo-approver", access.approverToken(), List.of("APPROVER")),
            new DemoProject(
                project.id().value(), project.source().value(), project.baseRevision().value()),
            Instant.now());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Pragma", "no-cache")
        .body(body);
  }

  public record DemoSession(
      String mode,
      DemoIdentity developer,
      DemoIdentity approver,
      DemoProject project,
      Instant issuedAt) {}

  public record DemoIdentity(String subject, String token, List<String> roles) {
    public DemoIdentity {
      roles = List.copyOf(roles);
    }
  }

  public record DemoProject(String id, String source, String revision) {}
}

package io.legacypilot.server.api;

import io.legacypilot.application.port.SourceFileReader.SourceReadRequest;
import io.legacypilot.application.port.SourceFileReader.SourceSnippet;
import io.legacypilot.application.service.ReadProjectSourceUseCase;
import io.legacypilot.server.service.SourceAccessAudit;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/source")
public class SourceController {

  private final ReadProjectSourceUseCase source;
  private final SourceAccessAudit audit;

  public SourceController(ReadProjectSourceUseCase source, SourceAccessAudit audit) {
    this.source = source;
    this.audit = audit;
  }

  @GetMapping
  SourceSnippet read(
      @PathVariable String projectId,
      @RequestParam String revision,
      @RequestParam String path,
      @RequestParam(defaultValue = "1") int startLine,
      @RequestParam(defaultValue = "1") int endLine,
      @RequestParam(defaultValue = "5") int contextLines,
      @RequestParam(defaultValue = "false") boolean fullFile,
      Authentication authentication) {
    var actor = authentication == null ? "anonymous" : authentication.getName();
    var result =
        source.read(
            projectId,
            new SourceReadRequest(revision, path, startLine, endLine, contextLines, fullFile));
    audit.record(
        "ALLOWED",
        actor,
        projectId,
        revision,
        path,
        result.startLine(),
        result.endLine(),
        fullFile,
        "-");
    return result;
  }
}

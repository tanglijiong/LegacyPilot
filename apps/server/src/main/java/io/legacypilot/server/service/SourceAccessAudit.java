package io.legacypilot.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Metadata-only audit trail. Source content and excerpts are deliberately never accepted here. */
@Component
public class SourceAccessAudit {

  private static final Logger LOGGER = LoggerFactory.getLogger("source-access-audit");

  public void record(
      String outcome,
      String actor,
      String projectId,
      String revision,
      String path,
      int startLine,
      int endLine,
      boolean fullFile,
      String reason) {
    LOGGER.info(
        "source_access outcome={} actor={} project={} revision={} path={} "
            + "startLine={} endLine={} fullFile={} reason={}",
        safe(outcome),
        safe(actor),
        safe(projectId),
        safe(revision),
        safe(path),
        startLine,
        endLine,
        fullFile,
        safe(reason));
  }

  private static String safe(String value) {
    if (value == null) {
      return "-";
    }
    return value.replaceAll("[\\r\\n\\t]", "_");
  }
}

package io.legacypilot.application.port;

import io.legacypilot.domain.project.Project;

/** Reads immutable source blobs from a registered project's pinned Git revision. */
public interface SourceFileReader {

  SourceSnippet read(Project project, SourceReadRequest request);

  record SourceReadRequest(
      String revision,
      String path,
      int startLine,
      int endLine,
      int contextLines,
      boolean fullFile) {}

  record SourceSnippet(
      String projectId,
      String revision,
      String path,
      int startLine,
      int endLine,
      int totalLines,
      String content,
      String contentSha256,
      String requestedRangeSha256,
      String snippetSha256,
      boolean truncated) {}
}

package io.legacypilot.application.port;

import java.util.Objects;

/** One browser-selected file used to create an immutable managed directory snapshot. */
public record DirectorySnapshotFile(String relativePath, byte[] content) {

  public DirectorySnapshotFile {
    Objects.requireNonNull(relativePath, "relativePath must not be null");
    Objects.requireNonNull(content, "content must not be null");
    content = content.clone();
  }

  @Override
  public byte[] content() {
    return content.clone();
  }
}

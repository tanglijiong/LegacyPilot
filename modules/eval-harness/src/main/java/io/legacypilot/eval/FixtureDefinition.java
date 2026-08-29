package io.legacypilot.eval;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record FixtureDefinition(
    String schemaVersion,
    String id,
    String source,
    String revision,
    String license,
    String sha256,
    Path path,
    List<String> buildCommand) {
  public FixtureDefinition {
    Objects.requireNonNull(schemaVersion);
    Objects.requireNonNull(id);
    Objects.requireNonNull(source);
    Objects.requireNonNull(revision);
    Objects.requireNonNull(license);
    Objects.requireNonNull(sha256);
    path = Objects.requireNonNull(path).toAbsolutePath().normalize();
    buildCommand = List.copyOf(Objects.requireNonNull(buildCommand));
    if (!schemaVersion.equals("eval-fixture-v1")
        || !id.matches("[a-z0-9][a-z0-9-]*")
        || source.isBlank()
        || revision.isBlank()
        || license.isBlank()
        || !sha256.matches("[0-9a-f]{64}")
        || buildCommand.isEmpty()
        || buildCommand.stream().anyMatch(String::isBlank)) {
      throw new IllegalArgumentException("eval fixture provenance is invalid");
    }
  }
}

package io.legacypilot.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;

public final class FileReportStore implements ReportStore {
  private final Path directory;
  private final ObjectMapper mapper;
  private final ReportRenderer renderer;

  public FileReportStore(Path directory, ObjectMapper mapper) {
    this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
    this.mapper = Objects.requireNonNull(mapper);
    this.renderer = new ReportRenderer(mapper);
    try {
      Files.createDirectories(this.directory);
    } catch (IOException exception) {
      throw new IllegalArgumentException("report directory is unavailable", exception);
    }
  }

  @Override
  public synchronized void save(RunReport report) {
    write(path(report.runId(), ".json"), renderer.json(report));
    write(path(report.runId(), ".md"), renderer.markdown(report));
  }

  @Override
  public synchronized Optional<RunReport> load(String runId) {
    var source = path(runId, ".json");
    if (!Files.exists(source)) {
      return Optional.empty();
    }
    try {
      return Optional.of(mapper.readValue(source.toFile(), RunReport.class));
    } catch (IOException exception) {
      throw new IllegalStateException("unable to load run report", exception);
    }
  }

  private void write(Path target, String content) {
    try {
      var temporary = Files.createTempFile(directory, ".report-", ".tmp");
      try {
        Files.writeString(temporary, content);
        Files.move(
            temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("unable to persist run report", exception);
    }
  }

  private Path path(String runId, String extension) {
    if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")) {
      throw new IllegalArgumentException("run id is invalid");
    }
    return directory.resolve(runId + extension);
  }
}

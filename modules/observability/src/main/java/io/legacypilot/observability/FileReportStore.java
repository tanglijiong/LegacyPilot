package io.legacypilot.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.state.VersionedJsonFile;
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
    state(report.runId()).save(report);
    write(path(report.runId(), ".md"), renderer.markdown(report));
  }

  @Override
  public synchronized Optional<RunReport> load(String runId) {
    return state(runId).load();
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

  private VersionedJsonFile<RunReport> state(String runId) {
    return new VersionedJsonFile<>(
        path(runId, ".json"), mapper, mapper.getTypeFactory().constructType(RunReport.class));
  }
}

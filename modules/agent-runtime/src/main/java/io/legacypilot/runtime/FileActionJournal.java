package io.legacypilot.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.observability.SensitiveDataRedactor;
import io.legacypilot.state.VersionedJsonFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class FileActionJournal implements ActionJournal {
  private final Path directory;
  private final ObjectMapper mapper;
  private final SensitiveDataRedactor redactor;

  public FileActionJournal(Path directory, ObjectMapper mapper) {
    this(directory, mapper, new SensitiveDataRedactor(2_048));
  }

  public FileActionJournal(Path directory, ObjectMapper mapper, SensitiveDataRedactor redactor) {
    this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
    this.mapper = Objects.requireNonNull(mapper);
    this.redactor = Objects.requireNonNull(redactor);
  }

  @Override
  public synchronized Optional<ActionRecord> find(String runId, String actionId) {
    return read(runId).stream().filter(value -> value.actionId().equals(actionId)).findFirst();
  }

  @Override
  public synchronized void save(ActionRecord record) {
    var safeRecord =
        new ActionRecord(
            record.actionId(),
            record.runId(),
            record.tool(),
            record.actionDigest(),
            record.planDigest(),
            record.status(),
            record.attempts(),
            redactor.redact("result", record.resultSummary()),
            record.updatedAt());
    var values = new ArrayList<>(read(safeRecord.runId()));
    values.removeIf(value -> value.actionId().equals(safeRecord.actionId()));
    values.add(safeRecord);
    values.sort(Comparator.comparing(ActionRecord::actionId));
    file(safeRecord.runId()).save(values);
  }

  @Override
  public synchronized List<ActionRecord> records(String runId) {
    return List.copyOf(read(runId));
  }

  private List<ActionRecord> read(String runId) {
    return file(runId).load().orElseGet(List::of);
  }

  private VersionedJsonFile<List<ActionRecord>> file(String runId) {
    validate(runId);
    var type = mapper.getTypeFactory().constructCollectionType(List.class, ActionRecord.class);
    return new VersionedJsonFile<>(directory.resolve(runId + ".json"), mapper, type);
  }

  private static void validate(String runId) {
    if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")) {
      throw new IllegalArgumentException("run id is invalid");
    }
  }
}

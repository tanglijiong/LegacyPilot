package io.legacypilot.runtime;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryActionJournal implements ActionJournal {
  private final ConcurrentHashMap<String, ActionRecord> values = new ConcurrentHashMap<>();

  @Override
  public Optional<ActionRecord> find(String runId, String actionId) {
    return Optional.ofNullable(values.get(key(runId, actionId)));
  }

  @Override
  public void save(ActionRecord record) {
    values.put(key(record.runId(), record.actionId()), record);
  }

  @Override
  public List<ActionRecord> records(String runId) {
    return values.values().stream()
        .filter(value -> value.runId().equals(runId))
        .sorted(Comparator.comparing(ActionRecord::actionId))
        .toList();
  }

  private static String key(String runId, String actionId) {
    return runId + "\n" + actionId;
  }
}

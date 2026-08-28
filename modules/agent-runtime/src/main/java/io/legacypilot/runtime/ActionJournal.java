package io.legacypilot.runtime;

import java.util.List;
import java.util.Optional;

public interface ActionJournal {
  Optional<ActionRecord> find(String runId, String actionId);

  void save(ActionRecord record);

  List<ActionRecord> records(String runId);
}

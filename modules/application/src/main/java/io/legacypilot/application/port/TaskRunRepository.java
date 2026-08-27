package io.legacypilot.application.port;

import io.legacypilot.domain.run.RunId;
import io.legacypilot.domain.run.TaskRun;
import java.util.Optional;

public interface TaskRunRepository {
  TaskRun add(TaskRun run);

  TaskRun update(TaskRun run);

  Optional<TaskRun> findById(RunId id);
}

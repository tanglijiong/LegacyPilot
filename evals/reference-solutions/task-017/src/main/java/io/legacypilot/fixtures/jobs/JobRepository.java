package io.legacypilot.fixtures.jobs;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JobRepository {
  void save(Job job);

  Optional<Job> findById(String id);

  default List<Job> findReadyScheduledBefore(Instant dueExclusive, int limit) {
    return List.of();
  }
}

package io.legacypilot.fixtures.jobs;

import java.util.Optional;

public interface JobRepository {
  void save(Job job);

  Optional<Job> findById(String id);
}

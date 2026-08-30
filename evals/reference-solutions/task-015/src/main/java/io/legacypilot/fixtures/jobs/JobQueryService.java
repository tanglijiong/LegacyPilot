package io.legacypilot.fixtures.jobs;

import java.util.Comparator;
import java.util.List;

public final class JobQueryService {
  public List<Job> page(List<Job> jobs, int offset, int limit) {
    if (offset < 0 || limit <= 0) {
      throw new IllegalArgumentException("INVALID_PAGE");
    }
    var ordered =
        jobs.stream()
            .sorted(Comparator.comparing(Job::scheduledAt).thenComparing(Job::id))
            .toList();
    if (offset >= ordered.size()) {
      return List.of();
    }
    return ordered.subList(offset, Math.min(ordered.size(), offset + limit));
  }
}

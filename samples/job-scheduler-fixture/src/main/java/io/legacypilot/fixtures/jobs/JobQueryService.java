package io.legacypilot.fixtures.jobs;

import java.util.List;

public final class JobQueryService {
  public List<Job> page(List<Job> jobs, int offset, int limit) {
    var end = Math.min(jobs.size(), offset + limit);
    return jobs.subList(offset, end);
  }
}

package io.legacypilot.context;

import io.legacypilot.analysis.java.ProjectIndex;
import java.util.List;

@FunctionalInterface
public interface Retriever {
  List<EvidenceCandidate> retrieve(ProjectIndex index, String query, int limit);
}

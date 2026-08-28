package io.legacypilot.context;

import java.util.List;

public interface VectorStore {
  void replaceRevision(String revision, String model, List<VectorEntry> entries);

  List<VectorMatch> search(String revision, EmbeddingVector query, int limit);

  int deleteRevision(String revision);

  int count(String revision, String model);
}

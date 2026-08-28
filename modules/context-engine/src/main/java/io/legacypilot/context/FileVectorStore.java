package io.legacypilot.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.state.VersionedJsonFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class FileVectorStore implements VectorStore {
  private final Path file;
  private final ObjectMapper mapper;

  public FileVectorStore(Path file, ObjectMapper mapper) {
    this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
    this.mapper = Objects.requireNonNull(mapper);
  }

  @Override
  public synchronized void replaceRevision(
      String revision, String model, List<VectorEntry> entries) {
    if (entries.stream()
        .anyMatch(entry -> !entry.revision().equals(revision) || !entry.model().equals(model))) {
      throw new IllegalArgumentException("vector entries do not match revision/model");
    }
    var values = new ArrayList<>(read());
    values.removeIf(entry -> entry.revision().equals(revision) && entry.model().equals(model));
    values.addAll(entries);
    state().save(values);
  }

  @Override
  public synchronized List<VectorMatch> search(String revision, EmbeddingVector query, int limit) {
    if (limit < 1 || limit > 10_000) {
      throw new IllegalArgumentException("vector search limit is invalid");
    }
    return read().stream()
        .filter(entry -> entry.revision().equals(revision) && entry.model().equals(query.model()))
        .filter(entry -> entry.vector().size() == query.values().size())
        .map(entry -> new VectorMatch(entry, cosine(entry.vector(), query.values())))
        .filter(match -> match.score() > 0)
        .sorted(
            Comparator.comparingDouble(VectorMatch::score)
                .reversed()
                .thenComparing(match -> match.entry().symbolId()))
        .limit(limit)
        .toList();
  }

  @Override
  public synchronized int deleteRevision(String revision) {
    var values = new ArrayList<>(read());
    var before = values.size();
    values.removeIf(entry -> entry.revision().equals(revision));
    if (values.size() != before) {
      state().save(values);
    }
    return before - values.size();
  }

  @Override
  public synchronized int count(String revision, String model) {
    return Math.toIntExact(
        read().stream()
            .filter(entry -> entry.revision().equals(revision) && entry.model().equals(model))
            .count());
  }

  private List<VectorEntry> read() {
    return state().load().orElseGet(List::of);
  }

  private VersionedJsonFile<List<VectorEntry>> state() {
    var type = mapper.getTypeFactory().constructCollectionType(List.class, VectorEntry.class);
    return new VersionedJsonFile<>(file, mapper, type);
  }

  private static double cosine(List<Double> left, List<Double> right) {
    double dot = 0;
    double leftNorm = 0;
    double rightNorm = 0;
    for (var index = 0; index < left.size(); index++) {
      dot += left.get(index) * right.get(index);
      leftNorm += left.get(index) * left.get(index);
      rightNorm += right.get(index) * right.get(index);
    }
    return leftNorm == 0 || rightNorm == 0 ? 0 : dot / Math.sqrt(leftNorm * rightNorm);
  }
}

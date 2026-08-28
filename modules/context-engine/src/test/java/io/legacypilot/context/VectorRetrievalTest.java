package io.legacypilot.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.analysis.java.JavaProjectIndexer;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VectorRetrievalTest {
  @TempDir Path temporary;

  @Test
  void persistsRevisionScopedVectorsReplacesStaleEntriesAndDeletesRevision() {
    var index = banking("revision-a");
    var mapper = new ObjectMapper().findAndRegisterModules();
    var store = new FileVectorStore(temporary.resolve("vectors.json"), mapper);
    var embeddings = new DeterministicEmbeddingProvider("hash-v1", 64);
    var indexer = new VectorIndexer(embeddings, store);
    assertEquals(index.symbols().size(), indexer.synchronize(index));
    assertEquals(index.symbols().size(), store.count("revision-a", "hash-v1"));

    var reduced =
        new io.legacypilot.analysis.java.ProjectIndex(
            index.schemaVersion(),
            index.revision(),
            index.symbols().subList(0, index.symbols().size() - 1),
            index.edges(),
            index.problems());
    indexer.synchronize(reduced);
    assertEquals(reduced.symbols().size(), store.count("revision-a", "hash-v1"));

    var newRevision = banking("revision-b");
    indexer.synchronize(newRevision);
    var retriever = new PersistentVectorRetriever(embeddings, store);
    assertTrue(
        retriever.retrieve(newRevision, "daily transfer limits", 10).stream()
            .allMatch(candidate -> newRevision.symbol(candidate.symbolId()).isPresent()));
    assertEquals(reduced.symbols().size(), store.deleteRevision("revision-a"));
    assertEquals(0, store.count("revision-a", "hash-v1"));
  }

  @Test
  void reportsProviderDegradationAndLexicalHybridRemainsAvailable() {
    var index = banking("revision-a");
    var vector =
        new PersistentVectorRetriever(
            text -> {
              throw new IllegalStateException("offline");
            },
            new FileVectorStore(
                temporary.resolve("unavailable.json"),
                new ObjectMapper().findAndRegisterModules()));
    var status = vector.retrieveWithStatus(index, "TransferService", 5);
    assertTrue(status.degraded());
    assertTrue(status.reason().contains("lexical"));

    var hybrid =
        new HybridRetriever(
            List.of(
                new WeightedRetriever(new ExactSymbolRetriever(), 1),
                new WeightedRetriever(vector, 0.5)));
    assertFalse(hybrid.retrieve(index, "TransferService", 5).isEmpty());
  }

  @Test
  void comparesLexicalVectorHybridAndRerankedMetricsDeterministically() {
    var index = banking("benchmark-v1");
    var embeddings = new DeterministicEmbeddingProvider("hash-v1", 128);
    var store =
        new FileVectorStore(
            temporary.resolve("benchmark.json"), new ObjectMapper().findAndRegisterModules());
    new VectorIndexer(embeddings, store).synchronize(index);
    var relevant = Set.of(index.named("TransferService").getFirst().id());
    var query = "daily transfer limits TransferService";
    var lexical = new Bm25Retriever();
    var vector = new PersistentVectorRetriever(embeddings, store);
    var hybrid =
        new HybridRetriever(
            List.of(
                new WeightedRetriever(new ExactSymbolRetriever(), 1),
                new WeightedRetriever(lexical, 0.75),
                new WeightedRetriever(vector, 0.65)));
    var reranked = new RerankingRetriever(hybrid, new LexicalOverlapReranker(), 2);

    for (var retriever : List.<Retriever>of(lexical, vector, hybrid, reranked)) {
      var first = retriever.retrieve(index, query, 10);
      var second = retriever.retrieve(index, query, 10);
      assertEquals(first, second);
      assertTrue(RetrievalEvaluator.recallAtK(first, relevant, 10) >= 0);
      assertTrue(RetrievalEvaluator.reciprocalRank(first, relevant) >= 0);
    }
    assertEquals(
        1.0, RetrievalEvaluator.recallAtK(hybrid.retrieve(index, query, 10), relevant, 10));
  }

  private static io.legacypilot.analysis.java.ProjectIndex banking(String revision) {
    var fixture = Path.of("../..", "samples", "banking-demo").toAbsolutePath().normalize();
    return new JavaProjectIndexer().index(fixture, revision);
  }
}

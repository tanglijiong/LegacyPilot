package io.legacypilot.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.legacypilot.analysis.java.JavaProjectIndexer;
import io.legacypilot.analysis.java.ProjectIndex;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RetrievalAndContextTest {

  private static ProjectIndex index;

  @BeforeAll
  static void indexFixture() {
    var fixture = Path.of("../..", "samples", "banking-demo").toAbsolutePath().normalize();
    index = new JavaProjectIndexer().index(fixture, "banking-fixture-v2");
  }

  @Test
  void retrievesByExactNameSignatureAnnotationAndErrorCode() {
    var retriever = new ExactSymbolRetriever();
    assertTrue(
        retriever
            .retrieve(index, "TransferService", 5)
            .getFirst()
            .summary()
            .contains("TransferService"));
    assertTrue(
        retriever.retrieve(index, "RestController", 5).stream()
            .anyMatch(candidate -> candidate.summary().contains("TransferController")));
    assertTrue(
        retriever.retrieve(index, "TransferRepository", 5).stream()
            .anyMatch(candidate -> candidate.path().endsWith("TransferRepository.java")));
    assertTrue(retriever.retrieve(index, "transfer(String,BigDecimal)", 10).size() >= 2);
    assertThrows(IllegalArgumentException.class, () -> retriever.retrieve(index, " ", 1));
  }

  @Test
  void retrievesNaturalLanguageWithBm25AndProducesStableEvidence() {
    var results = new Bm25Retriever().retrieve(index, "daily transfer limits", 10);
    assertFalse(results.isEmpty());
    assertTrue(
        results.stream().anyMatch(candidate -> candidate.path().endsWith("TransferService.java")));
    assertTrue(
        results.stream().allMatch(candidate -> candidate.sources().contains(RetrievalSource.BM25)));
    assertEquals(results, new Bm25Retriever().retrieve(index, "daily transfer limits", 10));
  }

  @Test
  void mergesHybridSourcesAndDegradesWhenVectorIsMissingOrFails() {
    var exact = new ExactSymbolRetriever();
    Retriever vector = (project, query, limit) -> exact.retrieve(project, query, limit);
    var hybrid =
        new HybridRetriever(
            List.of(
                new WeightedRetriever(exact, 1.0),
                new WeightedRetriever(new OptionalVectorRetriever(Optional.of(vector)), 0.5)));
    var results = hybrid.retrieve(index, "TransferService", 5);
    assertTrue(
        results
            .getFirst()
            .sources()
            .containsAll(Set.of(RetrievalSource.EXACT, RetrievalSource.VECTOR)));
    assertTrue(OptionalVectorRetriever.disabled().retrieve(index, "TransferService", 5).isEmpty());
    var failing =
        new OptionalVectorRetriever(
            Optional.of(
                (project, query, limit) -> {
                  throw new IllegalStateException("provider unavailable");
                }));
    assertTrue(failing.retrieve(index, "TransferService", 5).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> new HybridRetriever(List.of()));
    assertThrows(IllegalArgumentException.class, () -> new WeightedRetriever(exact, 0));
  }

  @Test
  void buildsGraphExpandedContextWithinBudgetWithStableReferences() {
    var builder = new ContextBuilder(new ExactSymbolRetriever(), TokenEstimator.conservative());
    var result =
        builder.build(
            index, new ContextRequest("transfer(String,BigDecimal):TransferRecord", 2_000, 10, 2));

    assertTrue(result.usedTokens() <= result.tokenBudget());
    assertTrue(
        result.chunks().stream()
            .anyMatch(chunk -> chunk.path().endsWith("TransferController.java")));
    assertTrue(
        result.chunks().stream().anyMatch(chunk -> chunk.path().endsWith("TransferService.java")));
    assertTrue(
        result.chunks().stream()
            .anyMatch(chunk -> chunk.path().endsWith("TransferRepository.java")));
    assertTrue(
        result.chunks().stream()
            .anyMatch(chunk -> chunk.sources().contains(RetrievalSource.GRAPH)));
    assertTrue(result.chunks().stream().allMatch(chunk -> chunk.content().startsWith("[ref-")));
    var again =
        builder.build(
            index, new ContextRequest("transfer(String,BigDecimal):TransferRecord", 2_000, 10, 2));
    assertEquals(result, again);
  }

  @Test
  void summarizesOrOmitsOversizedSymbolsAndRecordsDecisions() {
    Retriever one =
        (project, query, limit) ->
            List.of(
                CandidateSupport.candidate(
                    project.named("TransferService").getFirst(), 1, RetrievalSource.EXACT, "test"));
    var builder = new ContextBuilder(one, text -> Math.max(1, text.length() / 10));
    var result = builder.build(index, new ContextRequest("service", 20, 1, 0));
    assertTrue(result.usedTokens() <= 20);
    assertTrue(!result.chunks().isEmpty() || !result.omitted().isEmpty());
    if (!result.chunks().isEmpty()) {
      assertTrue(result.chunks().getFirst().reason().contains("summary"));
    }
    var empty =
        new ContextBuilder((project, query, limit) -> List.of(), TokenEstimator.conservative())
            .build(index, new ContextRequest("nothing", 10, 1, 0));
    assertTrue(empty.chunks().isEmpty());
    assertEquals(0, empty.usedTokens());
  }

  @Test
  void measuresRecallAndValidatesRequestsAndAccounting() {
    var results = new ExactSymbolRetriever().retrieve(index, "TransferService", 5);
    var relevant = Set.of(results.getFirst().symbolId());
    assertEquals(1.0, RetrievalEvaluator.recallAtK(results, relevant, 1));
    assertThrows(
        IllegalArgumentException.class, () -> RetrievalEvaluator.recallAtK(results, Set.of(), 1));
    assertThrows(IllegalArgumentException.class, () -> new ContextRequest("", 0, 0, -1));
    assertThrows(
        IllegalArgumentException.class, () -> new ContextBuildResult(List.of(), List.of(), 2, 1));
    assertTrue(TokenEstimator.conservative().estimate("中文 token estimate") > 0);
  }

  @Test
  void meetsPublishedBm25AndHybridRecallBaselineWithoutVectors() {
    var relevant =
        Set.of(index.named("io.legacypilot.samples.banking.TransferService").getFirst().id());
    var query = "daily transfer limits";
    var bm25 = new Bm25Retriever().retrieve(index, query, 10);
    var hybrid = HybridRetriever.defaults().retrieve(index, query, 10);
    assertEquals(1.0, RetrievalEvaluator.recallAtK(bm25, relevant, 10));
    assertEquals(1.0, RetrievalEvaluator.recallAtK(hybrid, relevant, 10));
  }
}

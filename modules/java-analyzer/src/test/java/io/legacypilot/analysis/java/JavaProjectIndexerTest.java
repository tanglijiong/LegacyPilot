package io.legacypilot.analysis.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaProjectIndexerTest {

  @TempDir Path temporary;

  @Test
  void matchesBankingGoldenSnapshotAndExtractsSpringSymbols() throws Exception {
    var root = bankingFixture();
    var golden = new Properties();
    try (var input = Files.newInputStream(root.resolve("golden-index.properties"))) {
      golden.load(input);
    }
    var index = new JavaProjectIndexer().index(root, golden.getProperty("revision"));

    assertEquals(Integer.parseInt(golden.getProperty("schemaVersion")), index.schemaVersion());
    assertEquals(Integer.parseInt(golden.getProperty("symbols")), index.symbols().size());
    assertTrue(index.problems().isEmpty());
    assertEquals(
        SpringRole.REST_CONTROLLER,
        index.named("TransferController").getFirst().springRoles().iterator().next());
    assertTrue(
        index.named("TransferService").getFirst().springRoles().contains(SpringRole.SERVICE));
    assertTrue(
        index.named("TransferRepository").getFirst().springRoles().contains(SpringRole.REPOSITORY));
    assertTrue(index.named("transfer(String,BigDecimal):TransferRecord").size() >= 2);
    assertTrue(index.symbols().stream().anyMatch(SourceSymbol::testSource));
    assertTrue(index.symbols().stream().allMatch(symbol -> symbol.range().start().line() > 0));

    var again = new JavaProjectIndexer().index(root, golden.getProperty("revision"));
    assertEquals(index.symbols(), again.symbols());
    assertEquals(index.edges(), again.edges());
  }

  @Test
  void buildsTraceableControllerServiceRepositoryGraphAndKeepsUnresolvedEdges() {
    var index = new JavaProjectIndexer().index(bankingFixture(), "revision-1");
    var controllerMethod =
        index.named("transfer(String,BigDecimal):TransferRecord").stream()
            .filter(symbol -> symbol.qualifiedName().contains("TransferController#"))
            .findFirst()
            .orElseThrow();
    var graph = new DependencyGraph(index);
    var downstream =
        graph.traverse(
            controllerMethod.id(),
            GraphDirection.DOWNSTREAM,
            2,
            Set.of(DependencyKind.METHOD_CALL),
            20);

    assertTrue(
        downstream.stream()
            .anyMatch(hit -> hit.symbol().qualifiedName().contains("TransferService#transfer")));
    assertTrue(
        downstream.stream()
            .anyMatch(hit -> hit.symbol().qualifiedName().contains("TransferRepository#")));
    assertTrue(downstream.stream().allMatch(hit -> hit.edge().evidence().start().line() > 0));
    assertTrue(
        index.edges().stream()
            .anyMatch(edge -> !edge.resolved() && edge.targetName().contains("BigDecimal")));
    var repositoryMethod =
        downstream.stream()
            .filter(hit -> hit.symbol().qualifiedName().contains("TransferRepository#"))
            .findFirst()
            .orElseThrow()
            .symbol();
    assertEquals(
        2,
        graph
            .findPath(
                controllerMethod.id(), repositoryMethod.id(), 2, Set.of(DependencyKind.METHOD_CALL))
            .orElseThrow()
            .size());
    assertFalse(
        graph.traverse(repositoryMethod.id(), GraphDirection.UPSTREAM, 2, Set.of(), 20).isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> graph.traverse("missing", GraphDirection.DOWNSTREAM, 1, Set.of(), 1));
  }

  @Test
  void supportsMultiModuleKindsAndReportsBrokenFilesWithoutStopping() throws Exception {
    var main = Files.createDirectories(temporary.resolve("module-a/src/main/java/example"));
    var test = Files.createDirectories(temporary.resolve("module-b/src/test/java/example"));
    Files.writeString(
        main.resolve("Kinds.java"),
        """
        package example;
        @interface Marker {}
        enum State { READY }
        interface Port {}
        class Outer { record Value(String text) {} }
        """);
    Files.writeString(test.resolve("Broken.java"), "package example; class Broken {");
    Files.createDirectories(temporary.resolve("target/generated"));
    Files.writeString(temporary.resolve("target/generated/Ignored.java"), "class Ignored {}");
    var outside = Files.createTempFile("outside", ".java");
    try {
      Files.createSymbolicLink(main.resolve("Linked.java"), outside);
      var index = new JavaProjectIndexer().index(temporary, "abc123");
      assertTrue(
          index.symbols().stream().anyMatch(symbol -> symbol.kind() == SymbolKind.ANNOTATION));
      assertTrue(index.symbols().stream().anyMatch(symbol -> symbol.kind() == SymbolKind.ENUM));
      assertTrue(
          index.symbols().stream().anyMatch(symbol -> symbol.kind() == SymbolKind.INTERFACE));
      assertTrue(index.symbols().stream().anyMatch(symbol -> symbol.kind() == SymbolKind.RECORD));
      assertTrue(
          index.problems().stream().anyMatch(problem -> problem.path().endsWith("Broken.java")));
      assertTrue(index.named("Ignored").isEmpty());
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void validatesValueObjectsAndGraphLimits() {
    assertThrows(IllegalArgumentException.class, () -> new SourcePosition(0, 1));
    assertThrows(IllegalArgumentException.class, () -> new IndexProblem("", 0, ""));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DependencyEdge(
                "",
                null,
                "",
                DependencyKind.IMPORTS,
                "x",
                new SourceRange(new SourcePosition(1, 1), new SourcePosition(1, 1)),
                2));
    assertThrows(
        IllegalArgumentException.class,
        () -> new JavaProjectIndexer().index(temporary.resolve("missing"), "rev"));
    var index = new JavaProjectIndexer().index(bankingFixture(), "rev");
    var graph = new DependencyGraph(index);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            graph.traverse(
                index.symbols().getFirst().id(), GraphDirection.DOWNSTREAM, 0, Set.of(), 1));
    assertTrue(
        graph
            .findPath(
                index.symbols().getFirst().id(),
                index.symbols().getLast().id(),
                1,
                Set.of(DependencyKind.EXTENDS))
            .isEmpty());
  }

  private static Path bankingFixture() {
    return Path.of("../..", "samples", "banking-demo").toAbsolutePath().normalize();
  }
}

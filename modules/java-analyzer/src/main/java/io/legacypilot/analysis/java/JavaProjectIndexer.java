package io.legacypilot.analysis.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithJavadoc;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class JavaProjectIndexer {

  private static final Set<String> SOURCE_ROOT_MARKERS = Set.of("main", "test");
  private final JavaParser parser;

  public JavaProjectIndexer() {
    var configuration =
        new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
            .setAttributeComments(false);
    this.parser = new JavaParser(configuration);
  }

  public ProjectIndex index(Path workspace, String revision) {
    Objects.requireNonNull(workspace, "workspace must not be null");
    Objects.requireNonNull(revision, "revision must not be null");
    if (revision.isBlank()) {
      throw new IllegalArgumentException("revision must not be blank");
    }
    var root = realDirectory(workspace);
    var symbols = new ArrayList<SourceSymbol>();
    var problems = new ArrayList<IndexProblem>();
    var units = new ArrayList<IndexedUnit>();
    var nodeIds = new IdentityHashMap<Node, String>();
    for (var file : sourceFiles(root)) {
      parse(root, file, symbols, problems, units, nodeIds);
    }
    var edges = buildEdges(units, symbols, nodeIds);
    symbols.sort(
        Comparator.comparing(SourceSymbol::path)
            .thenComparing(symbol -> symbol.range().start().line())
            .thenComparing(SourceSymbol::id));
    problems.sort(Comparator.comparing(IndexProblem::path).thenComparingInt(IndexProblem::line));
    return new ProjectIndex(
        ProjectIndex.CURRENT_SCHEMA_VERSION, revision, symbols, edges, problems);
  }

  private void parse(
      Path root,
      Path file,
      List<SourceSymbol> symbols,
      List<IndexProblem> problems,
      List<IndexedUnit> units,
      IdentityHashMap<Node, String> nodeIds) {
    var relative = relative(root, file);
    try {
      var result = parser.parse(file);
      result
          .getProblems()
          .forEach(problem -> problems.add(new IndexProblem(relative, 1, problem.getMessage())));
      result
          .getResult()
          .ifPresent(
              unit -> {
                var indexed = new IndexedUnit(relative, isTestSource(relative), unit);
                units.add(indexed);
                extractSymbols(indexed, symbols, nodeIds);
              });
    } catch (IOException exception) {
      problems.add(new IndexProblem(relative, 1, "Unable to read Java source"));
    } catch (RuntimeException exception) {
      problems.add(new IndexProblem(relative, 1, "Unable to index Java source"));
    }
  }

  private static void extractSymbols(
      IndexedUnit indexed, List<SourceSymbol> symbols, IdentityHashMap<Node, String> nodeIds) {
    var unit = indexed.unit();
    var packageName = unit.getPackageDeclaration().map(value -> value.getNameAsString()).orElse("");
    unit.getPackageDeclaration()
        .ifPresent(
            declaration ->
                addSymbol(
                    indexed,
                    declaration,
                    SymbolKind.PACKAGE,
                    declaration.getNameAsString(),
                    declaration.getNameAsString(),
                    declaration.toString().strip(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    "",
                    symbols,
                    nodeIds));
    unit.getImports()
        .forEach(
            declaration -> {
              var imported = declaration.getNameAsString() + (declaration.isAsterisk() ? ".*" : "");
              addSymbol(
                  indexed,
                  declaration,
                  SymbolKind.IMPORT,
                  imported,
                  imported,
                  declaration.toString().strip(),
                  Set.of(),
                  Set.of(),
                  Set.of(),
                  "",
                  symbols,
                  nodeIds);
            });
    types(unit)
        .forEach(
            declaration -> {
              var qualified = qualifiedTypeName(declaration, packageName);
              addSymbol(
                  indexed,
                  declaration,
                  kind(declaration),
                  declaration.getNameAsString(),
                  qualified,
                  typeSignature(declaration),
                  modifiers(declaration),
                  annotations(declaration),
                  springRoles(declaration),
                  javadoc(declaration),
                  symbols,
                  nodeIds);
              declaration
                  .getMembers()
                  .forEach(member -> extractMember(indexed, member, qualified, symbols, nodeIds));
            });
  }

  private static void extractMember(
      IndexedUnit indexed,
      BodyDeclaration<?> member,
      String owner,
      List<SourceSymbol> symbols,
      IdentityHashMap<Node, String> nodeIds) {
    if (member instanceof MethodDeclaration method) {
      addSymbol(
          indexed,
          method,
          SymbolKind.METHOD,
          method.getNameAsString(),
          owner + "#" + callableSignature(method),
          callableSignature(method),
          modifiers(method),
          annotations(method),
          Set.of(),
          javadoc(method),
          symbols,
          nodeIds);
    } else if (member instanceof ConstructorDeclaration constructor) {
      addSymbol(
          indexed,
          constructor,
          SymbolKind.CONSTRUCTOR,
          constructor.getNameAsString(),
          owner + "#" + callableSignature(constructor),
          callableSignature(constructor),
          modifiers(constructor),
          annotations(constructor),
          Set.of(),
          javadoc(constructor),
          symbols,
          nodeIds);
    } else if (member instanceof FieldDeclaration field) {
      field
          .getVariables()
          .forEach(
              variable ->
                  addSymbol(
                      indexed,
                      variable,
                      SymbolKind.FIELD,
                      variable.getNameAsString(),
                      owner + "#" + variable.getNameAsString(),
                      variable.getTypeAsString() + " " + variable.getNameAsString(),
                      modifiers(field),
                      annotations(field),
                      Set.of(),
                      javadoc(field),
                      symbols,
                      nodeIds));
    }
  }

  private static void addSymbol(
      IndexedUnit indexed,
      Node node,
      SymbolKind kind,
      String simpleName,
      String qualifiedName,
      String signature,
      Set<String> modifiers,
      Set<String> annotations,
      Set<SpringRole> roles,
      String javadoc,
      List<SourceSymbol> symbols,
      IdentityHashMap<Node, String> nodeIds) {
    var range = range(node);
    var identity =
        kind
            + "\u0000"
            + qualifiedName
            + "\u0000"
            + indexed.path()
            + "\u0000"
            + range.start().line();
    var id = "sym-" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    symbols.add(
        new SourceSymbol(
            id,
            kind,
            simpleName,
            qualifiedName,
            signature,
            indexed.path(),
            range,
            modifiers,
            annotations,
            roles,
            javadoc,
            node.toString(),
            indexed.testSource()));
    nodeIds.put(node, id);
  }

  private static List<DependencyEdge> buildEdges(
      List<IndexedUnit> units, List<SourceSymbol> symbols, IdentityHashMap<Node, String> nodeIds) {
    var resolver = new SymbolResolver(symbols);
    var edges = new LinkedHashMap<String, DependencyEdge>();
    for (var indexed : units) {
      var unit = indexed.unit();
      for (var type : types(unit)) {
        var typeId = nodeIds.get(type);
        if (typeId == null) {
          continue;
        }
        if (type instanceof ClassOrInterfaceDeclaration declaration) {
          declaration
              .getExtendedTypes()
              .forEach(
                  target ->
                      addEdge(
                          edges,
                          resolver.edge(
                              typeId,
                              target.getNameWithScope(),
                              DependencyKind.EXTENDS,
                              indexed.path(),
                              range(target))));
          declaration
              .getImplementedTypes()
              .forEach(
                  target ->
                      addEdge(
                          edges,
                          resolver.edge(
                              typeId,
                              target.getNameWithScope(),
                              DependencyKind.IMPLEMENTS,
                              indexed.path(),
                              range(target))));
        }
        addImportEdges(indexed, typeId, resolver, edges);
        addMemberEdges(indexed, type, typeId, resolver, nodeIds, edges);
      }
    }
    return edges.values().stream()
        .sorted(
            Comparator.comparing(DependencyEdge::fromSymbolId)
                .thenComparing(DependencyEdge::kind)
                .thenComparing(DependencyEdge::targetName))
        .toList();
  }

  private static void addImportEdges(
      IndexedUnit indexed,
      String typeId,
      SymbolResolver resolver,
      Map<String, DependencyEdge> edges) {
    indexed
        .unit()
        .getImports()
        .forEach(
            declaration ->
                addEdge(
                    edges,
                    resolver.edge(
                        typeId,
                        declaration.getNameAsString(),
                        DependencyKind.IMPORTS,
                        indexed.path(),
                        range(declaration))));
  }

  private static void addMemberEdges(
      IndexedUnit indexed,
      TypeDeclaration<?> type,
      String typeId,
      SymbolResolver resolver,
      IdentityHashMap<Node, String> nodeIds,
      Map<String, DependencyEdge> edges) {
    var variables = new HashMap<String, String>();
    type.getFields()
        .forEach(
            field ->
                field
                    .getVariables()
                    .forEach(
                        variable -> {
                          variables.put(variable.getNameAsString(), variable.getTypeAsString());
                          var kind =
                              hasInjectionAnnotation(field)
                                  ? DependencyKind.INJECTS
                                  : DependencyKind.FIELD_TYPE;
                          addEdge(
                              edges,
                              resolver.edge(
                                  typeId,
                                  variable.getTypeAsString(),
                                  kind,
                                  indexed.path(),
                                  range(variable)));
                        }));
    type.getConstructors()
        .forEach(
            constructor ->
                constructor
                    .getParameters()
                    .forEach(
                        parameter -> {
                          variables.put(parameter.getNameAsString(), parameter.getTypeAsString());
                          addEdge(
                              edges,
                              resolver.edge(
                                  nodeIds.get(constructor),
                                  parameter.getTypeAsString(),
                                  DependencyKind.INJECTS,
                                  indexed.path(),
                                  range(parameter)));
                        }));
    type.getMethods()
        .forEach(
            method -> {
              method
                  .getParameters()
                  .forEach(
                      parameter ->
                          addEdge(
                              edges,
                              resolver.edge(
                                  nodeIds.get(method),
                                  parameter.getTypeAsString(),
                                  DependencyKind.REFERENCES,
                                  indexed.path(),
                                  range(parameter))));
              if (!method.getType().isVoidType()) {
                addEdge(
                    edges,
                    resolver.edge(
                        nodeIds.get(method),
                        method.getTypeAsString(),
                        DependencyKind.REFERENCES,
                        indexed.path(),
                        range(method.getType())));
              }
            });
    type.findAll(MethodCallExpr.class)
        .forEach(
            call -> {
              var caller =
                  call.findAncestor(CallableDeclaration.class).map(nodeIds::get).orElse(typeId);
              var owner =
                  call.getScope()
                      .filter(NameExpr.class::isInstance)
                      .map(NameExpr.class::cast)
                      .map(NameExpr::getNameAsString)
                      .map(variables::get)
                      .orElse(type.getNameAsString());
              addEdge(
                  edges,
                  resolver.methodEdge(
                      caller, owner, call.getNameAsString(), indexed.path(), range(call)));
            });
  }

  private static void addEdge(Map<String, DependencyEdge> edges, DependencyEdge edge) {
    if (edge.fromSymbolId() != null) {
      var key = edge.fromSymbolId() + '\u0000' + edge.kind() + '\u0000' + edge.targetName();
      edges.putIfAbsent(key, edge);
    }
  }

  private static boolean hasInjectionAnnotation(FieldDeclaration field) {
    return annotations(field).stream()
        .anyMatch(name -> Set.of("Autowired", "Inject", "Resource").contains(simple(name)));
  }

  private static Path realDirectory(Path workspace) {
    try {
      var root = workspace.toRealPath();
      if (!Files.isDirectory(root)) {
        throw new IllegalArgumentException("workspace must be a directory");
      }
      return root;
    } catch (IOException exception) {
      throw new IllegalArgumentException("workspace is not accessible", exception);
    }
  }

  private static List<Path> sourceFiles(Path root) {
    try (var paths = Files.walk(root)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(path -> !Files.isSymbolicLink(path))
          .filter(JavaProjectIndexer::isJavaFile)
          .filter(path -> isSourcePath(relative(root, path)))
          .sorted()
          .toList();
    } catch (IOException exception) {
      throw new IllegalArgumentException("unable to discover Java sources", exception);
    }
  }

  private static boolean isSourcePath(String path) {
    var parts = Path.of(path);
    for (int index = 0; index + 2 < parts.getNameCount(); index++) {
      if (parts.getName(index).toString().equals("src")
          && SOURCE_ROOT_MARKERS.contains(parts.getName(index + 1).toString())
          && parts.getName(index + 2).toString().equals("java")) {
        return true;
      }
    }
    return false;
  }

  private static boolean isJavaFile(Path path) {
    var fileName = path.getFileName();
    return fileName != null && fileName.toString().endsWith(".java");
  }

  private static boolean isTestSource(String path) {
    return path.replace('\\', '/').contains("/src/test/java/") || path.startsWith("src/test/java/");
  }

  private static String relative(Path root, Path file) {
    try {
      return root.relativize(file.toRealPath()).toString().replace('\\', '/');
    } catch (IOException exception) {
      throw new IllegalArgumentException("unable to resolve source path", exception);
    }
  }

  private static SymbolKind kind(TypeDeclaration<?> declaration) {
    if (declaration instanceof ClassOrInterfaceDeclaration type) {
      return type.isInterface() ? SymbolKind.INTERFACE : SymbolKind.CLASS;
    }
    if (declaration instanceof EnumDeclaration) {
      return SymbolKind.ENUM;
    }
    if (declaration instanceof RecordDeclaration) {
      return SymbolKind.RECORD;
    }
    if (declaration instanceof AnnotationDeclaration) {
      return SymbolKind.ANNOTATION;
    }
    throw new IllegalArgumentException("unsupported Java type declaration");
  }

  private static String qualifiedTypeName(TypeDeclaration<?> declaration, String packageName) {
    var names = new ArrayList<String>();
    Node current = declaration;
    while (current instanceof TypeDeclaration<?> type) {
      names.addFirst(type.getNameAsString());
      current = type.getParentNode().orElse(null);
    }
    var nested = String.join(".", names);
    return packageName.isBlank() ? nested : packageName + "." + nested;
  }

  private static String typeSignature(TypeDeclaration<?> declaration) {
    return kind(declaration).name().toLowerCase(Locale.ROOT) + " " + declaration.getNameAsString();
  }

  @SuppressWarnings("unchecked")
  private static List<TypeDeclaration<?>> types(CompilationUnit unit) {
    var declarations = new ArrayList<TypeDeclaration<?>>();
    for (var node : unit.findAll(TypeDeclaration.class)) {
      declarations.add(node);
    }
    return List.copyOf(declarations);
  }

  private static String callableSignature(CallableDeclaration<?> declaration) {
    var parameters =
        declaration.getParameters().stream().map(parameter -> parameter.getTypeAsString()).toList();
    var suffix =
        declaration instanceof MethodDeclaration method ? ":" + method.getTypeAsString() : "";
    return declaration.getNameAsString() + "(" + String.join(",", parameters) + ")" + suffix;
  }

  private static Set<String> modifiers(NodeWithModifiers<?> node) {
    var values = new LinkedHashSet<String>();
    node.getModifiers()
        .forEach(modifier -> values.add(modifier.getKeyword().asString().toLowerCase(Locale.ROOT)));
    return Set.copyOf(values);
  }

  private static Set<String> annotations(NodeWithAnnotations<?> node) {
    var values = new LinkedHashSet<String>();
    node.getAnnotations().forEach(annotation -> values.add(annotation.getNameAsString()));
    return Set.copyOf(values);
  }

  private static Set<SpringRole> springRoles(NodeWithAnnotations<?> node) {
    var roles = new LinkedHashSet<SpringRole>();
    for (var annotation : annotations(node)) {
      switch (simple(annotation)) {
        case "Controller" -> roles.add(SpringRole.CONTROLLER);
        case "RestController" -> roles.add(SpringRole.REST_CONTROLLER);
        case "Service" -> roles.add(SpringRole.SERVICE);
        case "Repository" -> roles.add(SpringRole.REPOSITORY);
        case "Entity" -> roles.add(SpringRole.ENTITY);
        case "Configuration" -> roles.add(SpringRole.CONFIGURATION);
        case "Component" -> roles.add(SpringRole.COMPONENT);
        default -> {
          // Other annotations remain available on the symbol without implying a Spring role.
        }
      }
    }
    return Set.copyOf(roles);
  }

  private static String javadoc(NodeWithJavadoc<?> node) {
    return node.getJavadocComment().map(comment -> comment.getContent().strip()).orElse("");
  }

  private static SourceRange range(Node node) {
    return node.getRange()
        .map(
            value ->
                new SourceRange(
                    new SourcePosition(value.begin.line, value.begin.column),
                    new SourcePosition(value.end.line, value.end.column)))
        .orElseGet(() -> new SourceRange(new SourcePosition(1, 1), new SourcePosition(1, 1)));
  }

  private static String simple(String name) {
    var generics = name.indexOf('<');
    var clean = generics < 0 ? name : name.substring(0, generics);
    while (clean.endsWith("[]")) {
      clean = clean.substring(0, clean.length() - 2);
    }
    var separator = clean.lastIndexOf('.');
    return separator < 0 ? clean : clean.substring(separator + 1);
  }

  private record IndexedUnit(String path, boolean testSource, CompilationUnit unit) {}

  private static final class SymbolResolver {
    private final Map<String, List<SourceSymbol>> byName = new HashMap<>();

    private SymbolResolver(List<SourceSymbol> symbols) {
      symbols.stream()
          .filter(JavaProjectIndexer::isResolvable)
          .forEach(
              symbol -> {
                byName
                    .computeIfAbsent(symbol.simpleName(), ignored -> new ArrayList<>())
                    .add(symbol);
                byName
                    .computeIfAbsent(symbol.qualifiedName(), ignored -> new ArrayList<>())
                    .add(symbol);
              });
      byName.values().forEach(values -> values.sort(Comparator.comparing(SourceSymbol::id)));
    }

    private DependencyEdge edge(
        String from, String target, DependencyKind kind, String path, SourceRange evidence) {
      var resolved = resolveType(target);
      return new DependencyEdge(
          from,
          resolved.map(SourceSymbol::id).orElse(null),
          target,
          kind,
          path,
          evidence,
          resolved.isPresent() ? 0.9 : 0.35);
    }

    private DependencyEdge methodEdge(
        String from, String owner, String method, String path, SourceRange evidence) {
      var ownerType = resolveType(owner);
      var resolved =
          ownerType.flatMap(
              type ->
                  byName.values().stream()
                      .flatMap(List::stream)
                      .distinct()
                      .filter(symbol -> symbol.kind() == SymbolKind.METHOD)
                      .filter(symbol -> symbol.simpleName().equals(method))
                      .filter(
                          symbol -> symbol.qualifiedName().startsWith(type.qualifiedName() + "#"))
                      .min(Comparator.comparing(SourceSymbol::id)));
      var target = owner + "#" + method;
      return new DependencyEdge(
          from,
          resolved.map(SourceSymbol::id).orElse(null),
          target,
          DependencyKind.METHOD_CALL,
          path,
          evidence,
          resolved.isPresent() ? 1.0 : 0.3);
    }

    private Optional<SourceSymbol> resolveType(String raw) {
      var name = simple(raw);
      return Optional.ofNullable(byName.getOrDefault(raw, byName.getOrDefault(name, List.of())))
          .flatMap(values -> values.stream().filter(JavaProjectIndexer::isType).findFirst());
    }
  }

  private static boolean isResolvable(SourceSymbol symbol) {
    return isType(symbol) || symbol.kind() == SymbolKind.METHOD;
  }

  private static boolean isType(SourceSymbol symbol) {
    return Set.of(
            SymbolKind.CLASS,
            SymbolKind.INTERFACE,
            SymbolKind.ENUM,
            SymbolKind.RECORD,
            SymbolKind.ANNOTATION)
        .contains(symbol.kind());
  }
}

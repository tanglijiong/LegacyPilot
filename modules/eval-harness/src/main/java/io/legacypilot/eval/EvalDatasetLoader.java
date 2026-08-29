package io.legacypilot.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class EvalDatasetLoader {
  private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

  /** Loads the legacy v0.1 directory format without changing its published bytes. */
  public List<EvalTask> load(Path datasetDirectory) {
    var root = normalizedDirectory(datasetDirectory, "eval dataset is unavailable");
    if (Files.isRegularFile(root.resolve("manifest.yml"))) {
      return loadVersioned(root).tasks();
    }
    try (var paths = Files.list(root)) {
      var taskDirectories = paths.filter(Files::isDirectory).sorted().toList();
      var tasks = new ArrayList<EvalTask>();
      for (var taskDirectory : taskDirectories) {
        tasks.add(loadLegacyTask(taskDirectory));
      }
      if (tasks.isEmpty()) {
        throw new IllegalArgumentException("eval dataset contains no tasks");
      }
      return List.copyOf(tasks);
    } catch (IOException exception) {
      throw new IllegalStateException("unable to load eval dataset", exception);
    }
  }

  /** Loads and verifies the immutable v2 manifest and all registered fixtures. */
  public EvalDataset loadVersioned(Path datasetDirectory) {
    var root = normalizedDirectory(datasetDirectory, "eval dataset is unavailable");
    try {
      rejectSymlinks(root);
      var manifest =
          yaml.readValue(root.resolve("manifest.yml").toFile(), ManifestDefinition.class);
      validateManifest(manifest);
      var projectRoot = findProjectRoot(root);
      var fixtures = loadFixtures(root, projectRoot, manifest.fixtureRegistry());
      var tasks = new ArrayList<EvalTask>();
      for (var taskId : manifest.tasks()) {
        var taskDirectory = root.resolve(taskId).normalize();
        if (!root.equals(taskDirectory.getParent()) || !Files.isDirectory(taskDirectory)) {
          throw new IllegalArgumentException("eval task directory is invalid: " + taskId);
        }
        tasks.add(loadVersionedTask(taskDirectory, fixtures));
      }
      var actualDigest = EvalContentDigest.datasetSha256(root, manifest.tasks());
      if (!actualDigest.equals(manifest.datasetSha256())) {
        throw new IllegalArgumentException("eval dataset checksum mismatch");
      }
      return new EvalDataset(
          manifest.schemaVersion(), manifest.datasetVersion(), actualDigest, fixtures, tasks);
    } catch (IOException exception) {
      throw new IllegalStateException("unable to load versioned eval dataset", exception);
    }
  }

  private EvalTask loadLegacyTask(Path directory) throws IOException {
    var definition =
        yaml.readValue(directory.resolve("task.yml").toFile(), LegacyTaskDefinition.class);
    var assertions = loadAssertions(directory);
    var revision = Files.readString(directory.resolve("fixture.ref")).strip();
    var expectedFiles = sorted(definition.expectedFiles());
    return new EvalTask(
        definition.id(),
        definition.category(),
        "legacy",
        definition.category(),
        expectedFiles.size() > 1 ? "multi-file" : "single-file",
        definition.requirement(),
        revision,
        revision,
        expectedFiles,
        List.of("pom.xml", "src/test/java"),
        expectedFiles,
        sorted(definition.relevantSymbols()),
        definition.maximumSteps(),
        120,
        new EvalTask.ResourceBudget(32_000, 2_048, 1_000),
        assertions);
  }

  private EvalTask loadVersionedTask(Path directory, Map<String, FixtureDefinition> fixtures)
      throws IOException {
    var definition = yaml.readValue(directory.resolve("task.yml").toFile(), TaskDefinition.class);
    var fixture = fixtures.get(definition.fixtureId());
    if (fixture == null) {
      throw new IllegalArgumentException("eval task references an unknown fixture");
    }
    if (!Objects.requireNonNull(directory.getFileName()).toString().equals(definition.id())) {
      throw new IllegalArgumentException("eval task id does not match its directory");
    }
    return new EvalTask(
        definition.id(),
        definition.category(),
        definition.difficulty(),
        definition.changeType(),
        definition.expectedImpact(),
        definition.requirement(),
        fixture.id(),
        fixture.revision(),
        sorted(definition.allowedFiles()),
        sorted(definition.forbiddenFiles()),
        sorted(definition.expectedFiles()),
        sorted(definition.relevantSymbols()),
        definition.maximumSteps(),
        definition.timeoutSeconds(),
        definition.resourceBudget(),
        loadAssertions(directory));
  }

  private Map<String, FixtureDefinition> loadFixtures(
      Path datasetRoot, Path projectRoot, String registryPath) throws IOException {
    var registry = datasetRoot.resolve(Objects.requireNonNull(registryPath)).normalize();
    if (!registry.startsWith(projectRoot) || !Files.isDirectory(registry)) {
      throw new IllegalArgumentException("eval fixture registry is invalid");
    }
    var fixtures = new LinkedHashMap<String, FixtureDefinition>();
    try (var paths = Files.list(registry)) {
      for (var directory : paths.filter(Files::isDirectory).sorted().toList()) {
        var definition =
            yaml.readValue(directory.resolve("provenance.yml").toFile(), FixtureProvenance.class);
        var fixturePath = directory.resolve(definition.path()).normalize();
        if (!fixturePath.startsWith(projectRoot)
            || fixturePath.startsWith(projectRoot.resolve("evals/reference-solutions"))
            || !Files.isDirectory(fixturePath)) {
          throw new IllegalArgumentException("eval fixture path is outside the governed project");
        }
        rejectSymlinks(fixturePath);
        var fixture =
            new FixtureDefinition(
                definition.schemaVersion(),
                definition.id(),
                definition.source(),
                definition.revision(),
                definition.license(),
                definition.sha256(),
                fixturePath,
                definition.buildCommand());
        var actualDigest = EvalContentDigest.fixtureSha256(fixturePath);
        if (!actualDigest.equals(fixture.sha256())) {
          throw new IllegalArgumentException("eval fixture checksum mismatch: " + fixture.id());
        }
        if (fixtures.put(fixture.id(), fixture) != null) {
          throw new IllegalArgumentException("duplicate eval fixture id");
        }
      }
    }
    if (fixtures.isEmpty()) {
      throw new IllegalArgumentException("eval fixture registry is empty");
    }
    return Map.copyOf(fixtures);
  }

  private List<AssertionSpec> loadAssertions(Path directory) throws IOException {
    return Arrays.asList(
        yaml.readValue(directory.resolve("assertions.yml").toFile(), AssertionSpec[].class));
  }

  private static void validateManifest(ManifestDefinition manifest) {
    Objects.requireNonNull(manifest);
    if (!"eval-dataset-v2".equals(manifest.schemaVersion())
        || manifest.datasetVersion() == null
        || manifest.datasetVersion().isBlank()
        || manifest.datasetSha256() == null
        || !manifest.datasetSha256().matches("[0-9a-f]{64}")
        || manifest.fixtureRegistry() == null
        || manifest.fixtureRegistry().isBlank()
        || manifest.tasks() == null
        || manifest.tasks().isEmpty()
        || manifest.tasks().stream().anyMatch(id -> !id.matches("task-[0-9]{3}"))
        || new LinkedHashSet<>(manifest.tasks()).size() != manifest.tasks().size()) {
      throw new IllegalArgumentException("eval dataset manifest is invalid");
    }
  }

  private static Path findProjectRoot(Path start) {
    for (var current = start; current != null; current = current.getParent()) {
      if (Files.isRegularFile(current.resolve("pom.xml"))
          && Files.isDirectory(current.resolve("evals"))) {
        return current;
      }
    }
    throw new IllegalArgumentException("eval project root is unavailable");
  }

  private static Path normalizedDirectory(Path value, String message) {
    var path = Objects.requireNonNull(value).toAbsolutePath().normalize();
    if (!Files.isDirectory(path)) {
      throw new IllegalArgumentException(message);
    }
    return path;
  }

  private static void rejectSymlinks(Path root) throws IOException {
    try (var paths = Files.walk(root)) {
      if (paths.anyMatch(Files::isSymbolicLink)) {
        throw new IllegalArgumentException("eval content must not contain symbolic links");
      }
    }
  }

  private static List<String> sorted(List<String> values) {
    if (values == null) {
      return List.of();
    }
    return values.stream().map(Objects::requireNonNull).sorted(Comparator.naturalOrder()).toList();
  }

  private record ManifestDefinition(
      String schemaVersion,
      String datasetVersion,
      String datasetSha256,
      String fixtureRegistry,
      List<String> tasks) {}

  private record FixtureProvenance(
      String schemaVersion,
      String id,
      String source,
      String revision,
      String license,
      String sha256,
      String path,
      List<String> buildCommand) {}

  private record TaskDefinition(
      String id,
      String category,
      String difficulty,
      String changeType,
      String expectedImpact,
      String requirement,
      String fixtureId,
      List<String> allowedFiles,
      List<String> forbiddenFiles,
      List<String> expectedFiles,
      List<String> relevantSymbols,
      int maximumSteps,
      int timeoutSeconds,
      EvalTask.ResourceBudget resourceBudget) {}

  private record LegacyTaskDefinition(
      String id,
      String category,
      String requirement,
      List<String> expectedFiles,
      List<String> relevantSymbols,
      int maximumSteps) {}
}

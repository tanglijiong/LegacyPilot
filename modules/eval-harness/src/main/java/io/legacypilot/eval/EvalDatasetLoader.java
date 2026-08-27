package io.legacypilot.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class EvalDatasetLoader {
  private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

  public List<EvalTask> load(Path datasetDirectory) {
    var root = Objects.requireNonNull(datasetDirectory).toAbsolutePath().normalize();
    try (var paths = Files.list(root)) {
      var taskDirectories = paths.filter(Files::isDirectory).sorted().toList();
      var tasks = new ArrayList<EvalTask>();
      for (var taskDirectory : taskDirectories) {
        tasks.add(loadTask(taskDirectory));
      }
      if (tasks.isEmpty()) {
        throw new IllegalArgumentException("eval dataset contains no tasks");
      }
      return List.copyOf(tasks);
    } catch (IOException exception) {
      throw new IllegalStateException("unable to load eval dataset", exception);
    }
  }

  private EvalTask loadTask(Path directory) throws IOException {
    var definition = yaml.readValue(directory.resolve("task.yml").toFile(), TaskDefinition.class);
    var assertions =
        Arrays.asList(
            yaml.readValue(directory.resolve("assertions.yml").toFile(), AssertionSpec[].class));
    var revision = Files.readString(directory.resolve("fixture.ref")).strip();
    return new EvalTask(
        definition.id(),
        definition.category(),
        definition.requirement(),
        revision,
        sorted(definition.expectedFiles()),
        sorted(definition.relevantSymbols()),
        definition.maximumSteps(),
        assertions);
  }

  private static List<String> sorted(List<String> values) {
    return values.stream().sorted(Comparator.naturalOrder()).toList();
  }

  private record TaskDefinition(
      String id,
      String category,
      String requirement,
      List<String> expectedFiles,
      List<String> relevantSymbols,
      int maximumSteps) {}
}

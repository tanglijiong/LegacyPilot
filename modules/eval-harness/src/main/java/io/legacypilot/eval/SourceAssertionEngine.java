package io.legacypilot.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SourceAssertionEngine {

  public AssertionOutcome evaluate(Path workspace, List<AssertionSpec> assertions) {
    var root = Objects.requireNonNull(workspace).toAbsolutePath().normalize();
    var failures = new ArrayList<String>();
    int passed = 0;
    for (var assertion : assertions) {
      var target = root.resolve(assertion.path()).normalize();
      if (!target.startsWith(root)) {
        failures.add(assertion.path() + ": path escapes workspace");
        continue;
      }
      try {
        var success = evaluate(target, assertion);
        if (success) {
          passed++;
        } else {
          failures.add(assertion.path() + ": " + assertion.type() + " failed");
        }
      } catch (IOException exception) {
        failures.add(assertion.path() + ": unavailable");
      }
    }
    return new AssertionOutcome(passed, assertions.size(), failures);
  }

  private static boolean evaluate(Path target, AssertionSpec assertion) throws IOException {
    return switch (assertion.type()) {
      case "FILE_EXISTS" -> Files.isRegularFile(target);
      case "CONTAINS" ->
          Files.isRegularFile(target) && Files.readString(target).contains(assertion.value());
      case "NOT_CONTAINS" ->
          Files.isRegularFile(target) && !Files.readString(target).contains(assertion.value());
      default -> throw new IllegalArgumentException("unsupported assertion type");
    };
  }

  public record AssertionOutcome(int passed, int total, List<String> failures) {
    public AssertionOutcome {
      failures = List.copyOf(failures);
    }

    public boolean successful() {
      return passed == total;
    }
  }
}

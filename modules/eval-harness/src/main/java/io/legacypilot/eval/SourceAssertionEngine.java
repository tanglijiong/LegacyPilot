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
      case "FILE_NOT_EXISTS" -> !Files.exists(target);
      case "CONTAINS" ->
          Files.isRegularFile(target) && Files.readString(target).contains(assertion.value());
      case "NOT_CONTAINS" ->
          Files.isRegularFile(target) && !Files.readString(target).contains(assertion.value());
      case "MATCHES_REGEX" ->
          Files.isRegularFile(target)
              && java.util.regex.Pattern.compile(assertion.value(), java.util.regex.Pattern.DOTALL)
                  .matcher(Files.readString(target))
                  .find();
      case "NOT_MATCHES_REGEX" ->
          Files.isRegularFile(target)
              && !java.util.regex.Pattern.compile(assertion.value(), java.util.regex.Pattern.DOTALL)
                  .matcher(Files.readString(target))
                  .find();
      case "SHA256_EQUALS" ->
          Files.isRegularFile(target) && sha256(target).equals(assertion.value());
      default -> throw new IllegalArgumentException("unsupported assertion type");
    };
  }

  private static String sha256(Path target) throws IOException {
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(target)) {
        input.transferTo(
            new java.security.DigestOutputStream(
                new java.io.OutputStream() {
                  @Override
                  public void write(int value) {}

                  @Override
                  public void write(byte[] values, int offset, int length) {}
                },
                digest));
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
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

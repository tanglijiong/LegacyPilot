package io.legacypilot.eval;

import java.nio.file.Path;

@FunctionalInterface
public interface FixtureVerifier {
  Verification verify(Path workspace);

  record Verification(boolean compiled, boolean testsPassed, String summary) {}
}

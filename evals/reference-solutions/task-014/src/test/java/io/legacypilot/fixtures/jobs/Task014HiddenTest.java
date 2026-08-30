package io.legacypilot.fixtures.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class Task014HiddenTest {
  @Test
  void acceptsOnlyPrioritiesOneThroughFive() {
    var parser = new JobRequestParser();

    assertEquals(3, parser.parsePriority(" 3 "));
    assertThrows(IllegalArgumentException.class, () -> parser.parsePriority(null));
    assertThrows(IllegalArgumentException.class, () -> parser.parsePriority(""));
    assertThrows(IllegalArgumentException.class, () -> parser.parsePriority("0"));
    assertThrows(IllegalArgumentException.class, () -> parser.parsePriority("6"));
    assertThrows(IllegalArgumentException.class, () -> parser.parsePriority("high"));
  }
}

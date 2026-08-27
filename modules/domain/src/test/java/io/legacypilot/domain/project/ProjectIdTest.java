package io.legacypilot.domain.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProjectIdTest {

  @Test
  void keepsAValidValue() {
    var projectId = new ProjectId("legacy-pilot");

    assertEquals("legacy-pilot", projectId.value());
  }

  @Test
  void rejectsBlankValues() {
    assertThrows(IllegalArgumentException.class, () -> new ProjectId("  "));
  }

  @Test
  void rejectsNullValues() {
    assertThrows(NullPointerException.class, () -> new ProjectId(null));
  }
}

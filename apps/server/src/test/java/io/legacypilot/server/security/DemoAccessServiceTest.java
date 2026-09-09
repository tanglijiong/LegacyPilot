package io.legacypilot.server.security;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DemoAccessServiceTest {

  @Test
  void requiresLoopbackBindingAndCreatesSeparatedRandomCredentials() {
    assertThrows(IllegalStateException.class, () -> new DemoAccessService("0.0.0.0"));

    var access = new DemoAccessService("127.0.0.1");
    assertNotEquals(access.developerToken(), access.approverToken());
    assertTrue(access.developerToken().length() >= 40);
    assertTrue(access.approverToken().length() >= 40);
  }
}

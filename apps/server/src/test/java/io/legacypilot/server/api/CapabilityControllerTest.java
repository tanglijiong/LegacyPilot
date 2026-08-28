package io.legacypilot.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.legacypilot.observability.InMemoryTraceSink;
import io.legacypilot.observability.SensitiveDataRedactor;
import io.legacypilot.runtime.CapabilityRequest;
import io.legacypilot.runtime.CapabilityService;
import io.legacypilot.runtime.InMemoryCapabilityGrantStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CapabilityControllerTest {
  @Test
  void issuesListsFindsAndRevokesCapabilitiesWithoutExposingTokenDigest() {
    var now = Instant.parse("2026-08-28T00:00:00Z");
    var service =
        new CapabilityService(
            new InMemoryCapabilityGrantStore(),
            new InMemoryTraceSink(new SensitiveDataRedactor(8_192)),
            Clock.fixed(now, ZoneOffset.UTC));
    var controller = new CapabilityController(service);
    var issued =
        controller.issue(
            new CapabilityRequest(
                "alice",
                "mcp-1",
                "run-1",
                "apply_patch",
                Path.of("."),
                "a".repeat(64),
                "",
                now.plusSeconds(60),
                1));
    assertEquals(1, controller.list().size());
    assertEquals(issued.capability(), controller.find(issued.capability().id()));
    assertTrue(controller.revoke(issued.capability().id()).revoked());
    assertThrows(IllegalArgumentException.class, () -> controller.find("missing"));
    assertThrows(IllegalArgumentException.class, () -> controller.revoke("missing"));
  }
}

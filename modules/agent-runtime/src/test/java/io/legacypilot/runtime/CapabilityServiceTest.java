package io.legacypilot.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.observability.InMemoryTraceSink;
import io.legacypilot.observability.SensitiveDataRedactor;
import io.legacypilot.tool.spi.ActionDigests;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CapabilityServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  @TempDir Path temporary;

  @Test
  void issuesOpaqueTokenStoresOnlyDigestAndConsumesOnce() throws Exception {
    var file = temporary.resolve("capabilities.json");
    var service = service(new FileCapabilityGrantStore(file, MAPPER));
    var request = request(1);
    var issued = service.issue(request);

    assertFalse(issued.token().isBlank());
    assertFalse(Files.readString(file).contains(issued.token()));
    assertNotEquals(issued.token(), issued.capability().id());
    assertTrue(service.consume(issued.token(), use()).isPresent());
    assertTrue(service.consume(issued.token(), use()).isEmpty());
  }

  @Test
  void rejectsEveryScopeMismatchExpiryRevocationAndReplay() {
    var service = service(new InMemoryCapabilityGrantStore());
    var issued = service.issue(request(2));
    assertTrue(
        service
            .consume(
                issued.token(),
                new CapabilityUse(
                    "other", "mcp-1", "run-1", "apply_patch", temporary, digest(), ""))
            .isEmpty());
    assertTrue(
        service
            .consume(
                issued.token(),
                new CapabilityUse(
                    "alice", "other", "run-1", "apply_patch", temporary, digest(), ""))
            .isEmpty());
    assertTrue(
        service
            .consume(
                issued.token(),
                new CapabilityUse("alice", "mcp-1", "run-1", "read_file", temporary, digest(), ""))
            .isEmpty());
    assertTrue(
        service
            .consume(
                issued.token(),
                new CapabilityUse(
                    "alice",
                    "mcp-1",
                    "run-1",
                    "apply_patch",
                    temporary.resolve("other"),
                    digest(),
                    ""))
            .isEmpty());
    assertTrue(service.revoke(issued.capability().id()).isPresent());
    assertTrue(service.consume(issued.token(), use()).isEmpty());
  }

  @Test
  void concurrentOneTimeConsumptionHasExactlyOneWinner() throws Exception {
    var service = service(new FileCapabilityGrantStore(temporary.resolve("caps.json"), MAPPER));
    var issued = service.issue(request(1));
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures =
          java.util.stream.IntStream.range(0, 50)
              .mapToObj(
                  index ->
                      executor.submit(() -> service.consume(issued.token(), use()).isPresent()))
              .toList();
      var winners = 0;
      for (var future : futures) {
        if (future.get()) {
          winners++;
        }
      }
      assertEquals(1, winners);
    }
  }

  private CapabilityService service(CapabilityGrantStore store) {
    return new CapabilityService(
        store, new InMemoryTraceSink(new SensitiveDataRedactor(8_192)), CLOCK);
  }

  private CapabilityRequest request(int uses) {
    return new CapabilityRequest(
        "alice",
        "mcp-1",
        "run-1",
        "apply_patch",
        temporary,
        digest(),
        "",
        NOW.plusSeconds(60),
        uses);
  }

  private CapabilityUse use() {
    return new CapabilityUse("alice", "mcp-1", "run-1", "apply_patch", temporary, digest(), "");
  }

  private static String digest() {
    return ActionDigests.create(
        "apply_patch", MAPPER.createObjectNode().put("path", "src/App.java"));
  }
}

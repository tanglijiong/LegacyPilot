package io.legacypilot.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionedJsonFileTest {
  @TempDir Path temporary;
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void migratesLegacyPayloadAndKeepsBackup() throws Exception {
    var path = temporary.resolve("state.json");
    mapper.writeValue(path.toFile(), Map.of("value", "legacy"));
    var file = file(path);

    assertEquals(StateHealth.LEGACY, file.inspect().health());
    assertEquals("legacy", file.load().orElseThrow().get("value"));
    assertEquals(StateHealth.CURRENT, file.inspect().health());
    assertTrue(Files.exists(temporary.resolve("state.json.v1.bak")));
  }

  @Test
  void rejectsUnknownVersionWithoutOverwritingIt() throws Exception {
    var path = temporary.resolve("future.json");
    Files.writeString(path, "{\"schemaVersion\":99,\"payload\":{\"value\":\"future\"}}");
    var before = Files.readString(path);

    assertEquals(StateHealth.UNSUPPORTED, file(path).inspect().health());
    assertThrows(UnsupportedStateVersionException.class, () -> file(path).load());
    assertEquals(before, Files.readString(path));
  }

  @Test
  void quarantinesTruncatedState() throws Exception {
    var path = temporary.resolve("broken.json");
    Files.writeString(path, "{\"schemaVersion\":2,");

    assertEquals(StateHealth.CORRUPT, file(path).inspect().health());
    assertThrows(IllegalStateException.class, () -> file(path).load());
    assertTrue(Files.exists(temporary.resolve("broken.json.corrupt")));
  }

  @Test
  void classifiesOneHundredDeterministicInterruptedWritesWithoutOverwritingEvidence()
      throws Exception {
    for (var index = 0; index < 100; index++) {
      var path = temporary.resolve("fault-" + index + ".json");
      var interrupted = "{\"schemaVersion\":2,\"payload\":{" + " ".repeat(index);
      Files.writeString(path, interrupted);

      assertEquals(StateHealth.CORRUPT, file(path).inspect().health());
      assertThrows(IllegalStateException.class, () -> file(path).load());
      assertEquals(interrupted, Files.readString(path));
      assertTrue(Files.exists(path.resolveSibling(path.getFileName() + ".corrupt")));
    }
  }

  private VersionedJsonFile<Map<String, String>> file(Path path) {
    return new VersionedJsonFile<>(
        path,
        mapper,
        mapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
  }
}

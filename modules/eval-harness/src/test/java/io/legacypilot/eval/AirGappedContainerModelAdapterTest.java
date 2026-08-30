package io.legacypilot.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AirGappedContainerModelAdapterTest {
  private static final String IMAGE = "registry.bank.local/model-agent@sha256:" + "a".repeat(64);

  @TempDir Path directory;

  @Test
  void buildsAPinnedReadOnlyContainerWithNetworkingDisabled() throws Exception {
    var docker = directory.resolve("docker");
    Files.writeString(docker, "#!/bin/sh\nexit 0\n");
    assertTrue(docker.toFile().setExecutable(true));
    var workspace = directory.resolve("workspace");
    Files.createDirectories(workspace);

    var adapter =
        new AirGappedContainerModelAdapter(
            docker, IMAGE, "/opt/bank/model-agent", "bank-code-model", "high", new ObjectMapper());
    var command =
        AirGappedContainerModelAdapter.command(
            docker, IMAGE, "/opt/bank/model-agent", "bank-code-model", "high", workspace);

    assertEquals(NetworkBoundary.AIR_GAPPED, adapter.networkBoundary());
    assertEquals("airgap-container", adapter.adapterId());
    assertTrue(command.contains("none"));
    assertEquals("none", command.get(command.indexOf("--network") + 1));
    assertEquals("never", command.get(command.indexOf("--pull") + 1));
    assertTrue(command.contains("--read-only"));
    assertTrue(command.contains("no-new-privileges"));
    assertEquals("1000:1000", command.get(command.indexOf("--user") + 1));
    assertTrue(command.contains(IMAGE));
    assertFalse(command.stream().anyMatch(value -> value.contains("api.openai.com")));
  }

  @Test
  void rejectsMutableImagesAndShellCommands() throws Exception {
    var docker = directory.resolve("docker");
    Files.writeString(docker, "#!/bin/sh\nexit 0\n");
    assertTrue(docker.toFile().setExecutable(true));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AirGappedContainerModelAdapter(
                docker,
                "registry.bank.local/model-agent:latest",
                "/opt/bank/model-agent",
                "bank-code-model",
                "high",
                new ObjectMapper()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AirGappedContainerModelAdapter(
                docker,
                IMAGE,
                "/bin/sh -c curl evil.example",
                "bank-code-model",
                "high",
                new ObjectMapper()));
  }

  @Test
  void removesCredentialsAndProxyRoutesFromChildProcesses() {
    var environment =
        new HashMap<String, String>(
            java.util.Map.of(
                "OPENAI_API_KEY", "sensitive",
                "HTTPS_PROXY", "https://proxy.example",
                "SAFE_SETTING", "retained"));

    JsonlProcessModelAdapter.scrubSensitiveEnvironment(environment);

    assertFalse(environment.containsKey("OPENAI_API_KEY"));
    assertFalse(environment.containsKey("HTTPS_PROXY"));
    assertEquals("retained", environment.get("SAFE_SETTING"));
    assertEquals("*", environment.get("NO_PROXY"));
  }
}

package io.legacypilot.eval;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenFixtureVerifierTest {
  @TempDir Path directory;

  @Test
  void alwaysInvokesMavenOfflineWithoutCredentialOrProxyEnvironment() throws Exception {
    var arguments = directory.resolve("arguments.txt");
    var environment = directory.resolve("environment.txt");
    var wrapper = directory.resolve("mvnw");
    Files.writeString(
        wrapper,
        "#!/bin/sh\nprintf '%s\\n' \"$@\" > '"
            + arguments
            + "'\nenv > '"
            + environment
            + "'\nexit 0\n");
    assertTrue(wrapper.toFile().setExecutable(true));
    var workspace = directory.resolve("workspace");
    Files.createDirectories(workspace);
    Files.writeString(workspace.resolve("pom.xml"), "<project/>");

    var result = new MavenFixtureVerifier(wrapper, Duration.ofSeconds(10)).verify(workspace);

    assertTrue(result.testsPassed());
    assertTrue(Files.readAllLines(arguments).contains("--offline"));
    var childEnvironment = Files.readString(environment);
    assertTrue(childEnvironment.contains("NO_PROXY=*"));
  }
}

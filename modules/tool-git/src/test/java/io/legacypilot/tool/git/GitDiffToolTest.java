package io.legacypilot.tool.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.tool.spi.ToolContext;
import io.legacypilot.tool.spi.ToolErrorCode;
import io.legacypilot.tool.spi.ToolFailureException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitDiffToolTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  @TempDir Path workspace;

  @Test
  void returnsDiffStatisticsAndDrainsOversizedOutput() throws Exception {
    git("init");
    git("config", "user.email", "test@example.invalid");
    git("config", "user.name", "Test");
    var file = workspace.resolve("example.txt");
    Files.writeString(file, "before\n");
    git("add", "example.txt");
    git("commit", "-m", "initial");
    Files.writeString(file, "after\n");
    var context = new ToolContext("run", workspace, Set.of(), false);
    var tool = new GitDiffTool();
    var output = tool.execute(context, MAPPER.createObjectNode());
    assertTrue(output.path("diff").asText().contains("+after"));
    assertTrue(output.path("numstat").asText().contains("example.txt"));

    Files.writeString(file, "changed-line-that-is-long\n".repeat(60_000));
    assertTrue(tool.execute(context, MAPPER.createObjectNode()).path("truncated").asBoolean());
    assertEquals("git_diff", tool.descriptor().name());
  }

  @Test
  void reportsACommandFailureOutsideGitRepository() {
    var failure =
        assertThrows(
            ToolFailureException.class,
            () ->
                new GitDiffTool()
                    .execute(
                        new ToolContext("run", workspace, Set.of(), false),
                        MAPPER.createObjectNode()));
    assertEquals(ToolErrorCode.COMMAND_FAILED, failure.code());
  }

  private void git(String... arguments) throws Exception {
    var command = new java.util.ArrayList<String>();
    command.add("git");
    command.addAll(java.util.List.of(arguments));
    var process =
        new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true).start();
    assertEquals(0, process.waitFor(), new String(process.getInputStream().readAllBytes()));
  }
}

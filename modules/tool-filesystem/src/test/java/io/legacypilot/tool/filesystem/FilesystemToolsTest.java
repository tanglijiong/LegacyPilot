package io.legacypilot.tool.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.tool.spi.DefaultExecutionPolicy;
import io.legacypilot.tool.spi.ToolContext;
import io.legacypilot.tool.spi.ToolErrorCode;
import io.legacypilot.tool.spi.ToolExecutor;
import io.legacypilot.tool.spi.ToolFailureException;
import io.legacypilot.tool.spi.ToolRegistry;
import io.legacypilot.tool.spi.ToolStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemToolsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  @TempDir Path workspace;
  private Path source;
  private ToolContext context;

  @BeforeEach
  void setUp() throws Exception {
    source = Files.createDirectories(workspace.resolve("src/main/java")).resolve("Example.java");
    Files.writeString(source, "class Example {\n  Example field;\n}\n");
    Files.writeString(workspace.resolve("ignored.bin"), "Example");
    context = new ToolContext("run", workspace, Set.of(), false);
  }

  @Test
  void readsBoundedRangesAndRejectsInvalidPaths() throws Exception {
    var tool = new ReadFileTool();
    var output =
        tool.execute(
            context,
            MAPPER.readTree(
                "{\"path\":\"src/main/java/Example.java\",\"startLine\":2,\"endLine\":2}"));
    assertEquals("  Example field;", output.path("content").asText());
    assertTrue(output.path("truncated").asBoolean());
    assertThrows(
        ToolFailureException.class,
        () -> tool.execute(context, MAPPER.readTree("{\"path\":\"../outside\"}")));
    assertThrows(
        ToolFailureException.class,
        () ->
            tool.execute(
                context,
                MAPPER.readTree(
                    "{\"path\":\"src/main/java/Example.java\",\"startLine\":3,\"endLine\":2}")));
  }

  @Test
  void searchesLiteralTextAndWholeWordReferencesWithCaps() throws Exception {
    var search =
        new SearchCodeTool()
            .execute(context, MAPPER.readTree("{\"query\":\"Example\",\"maxMatches\":1}"));
    assertEquals(1, search.path("matches").size());
    assertTrue(search.path("truncated").asBoolean());
    Files.writeString(source, "Example ExtendedExample Example$Value\n");
    var references =
        SearchCodeTool.findReferences()
            .execute(context, MAPPER.readTree("{\"query\":\"Example\",\"maxMatches\":10}"));
    assertEquals(1, references.path("matches").size());
    assertThrows(
        ToolFailureException.class,
        () -> new SearchCodeTool().execute(context, MAPPER.readTree("{\"query\":\" \"}")));
  }

  @Test
  void createsApprovalBoundPatchAppliesAtomicallyAndDetectsConflicts() throws Exception {
    var create = new CreatePatchTool();
    var apply = new ApplyPatchTool(List.of("src/**"));
    var registry = new ToolRegistry(List.of(create, apply));
    var executor = new ToolExecutor(registry, new DefaultExecutionPolicy(), MAPPER);
    var patch =
        create.execute(
            context,
            MAPPER.readTree(
                "{\"path\":\"src/main/java/Example.java\",\"replacement\":\"class Changed {}\\n\"}"));
    assertTrue(patch.path("preview").asText().contains("guarded replacement"));
    var applyInput =
        MAPPER
            .createObjectNode()
            .put("path", patch.path("path").asText())
            .put("expectedSha256", patch.path("expectedSha256").asText())
            .put("replacement", patch.path("replacement").asText());
    var approval = executor.execute("apply_patch", context, applyInput);
    assertEquals(ToolStatus.APPROVAL_REQUIRED, approval.status());
    var approved = new ToolContext("run", workspace, Set.of(approval.actionDigest()), false);
    var applied = executor.execute("apply_patch", approved, applyInput);
    assertTrue(applied.successful(), () -> applied.status() + " " + applied.error());
    assertEquals("class Changed {}\n", Files.readString(source));
    var conflict = executor.execute("apply_patch", approved, applyInput);
    assertEquals(ToolErrorCode.PATCH_CONFLICT, conflict.error().code());
  }

  @Test
  void deniesNonWritableTargetsAndSymlinkEscapes() throws Exception {
    var apply = new ApplyPatchTool(List.of("src/**"));
    var digest = PatchSupport.sha256("Example");
    var denied =
        MAPPER
            .createObjectNode()
            .put("path", "ignored.bin")
            .put("expectedSha256", digest)
            .put("replacement", "changed");
    var failure = assertThrows(ToolFailureException.class, () -> apply.execute(context, denied));
    assertEquals(ToolErrorCode.PATH_VIOLATION, failure.code());
    assertThrows(IllegalArgumentException.class, () -> new ApplyPatchTool(List.of()));

    var outside = Files.createTempFile("legacypilot-outside", ".txt");
    try {
      var link = workspace.resolve("src/link.txt");
      Files.createSymbolicLink(link, outside);
      var linked =
          MAPPER
              .createObjectNode()
              .put("path", "src/link.txt")
              .put("expectedSha256", PatchSupport.sha256(Files.readString(outside)))
              .put("replacement", "changed");
      assertThrows(ToolFailureException.class, () -> apply.execute(context, linked));
      assertFalse(Files.readString(outside).equals("changed"));
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void createsDigestBoundNewFilesInsideWritableGlobs() throws Exception {
    var create = new CreatePatchTool();
    var patch =
        create.execute(
            context,
            MAPPER.readTree(
                "{\"path\":\"src/main/java/NewType.java\",\"replacement\":\"record NewType() {}\\n\"}"));
    assertTrue(patch.path("createsFile").asBoolean());
    var input =
        MAPPER
            .createObjectNode()
            .put("path", patch.path("path").asText())
            .put("expectedSha256", patch.path("expectedSha256").asText())
            .put("replacement", patch.path("replacement").asText());

    new ApplyPatchTool(List.of("src/**")).execute(context, input);

    assertEquals(
        "record NewType() {}\n", Files.readString(workspace.resolve("src/main/java/NewType.java")));
  }
}

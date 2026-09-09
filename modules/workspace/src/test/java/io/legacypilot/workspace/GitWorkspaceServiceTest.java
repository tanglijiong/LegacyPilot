package io.legacypilot.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.legacypilot.application.port.DirectorySnapshotFile;
import io.legacypilot.domain.project.Project;
import io.legacypilot.domain.project.ProjectId;
import io.legacypilot.domain.project.RepositoryLocation;
import io.legacypilot.domain.run.RunId;
import io.legacypilot.domain.run.WorkspaceId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitWorkspaceServiceTest {

  @TempDir Path temporaryDirectory;

  @Test
  void registersFixedRevisionCreatesRecoverableWorktreeAndCleansIt() throws IOException {
    var source = createRepository(temporaryDirectory.resolve("source"));
    var service = service();
    var registered = service.register(new RepositoryLocation(source.toString()), "HEAD");
    var project =
        new Project(
            new ProjectId("project-1"),
            new RepositoryLocation(source.toString()),
            registered.repositoryPath(),
            registered.revision(),
            Instant.EPOCH);
    var runId = new RunId("run-1");

    var workspace = service.create(project, runId);
    assertTrue(Files.isRegularFile(Path.of(workspace.path()).resolve("tracked.txt")));
    assertEquals(
        registered.revision().value(), git(Path.of(workspace.path()), "rev-parse", "HEAD"));
    assertEquals(workspace, service.create(project, runId));
    assertEquals(workspace, service.open(project, runId, workspace.id()));

    Files.writeString(source.resolve("second.txt"), "new branch content\n");
    git(source, "add", "second.txt");
    git(source, "commit", "-m", "move source branch");
    var moved = service.register(new RepositoryLocation(source.toString()), "HEAD");
    var movedProject =
        new Project(
            new ProjectId("project-moved"),
            new RepositoryLocation(source.toString()),
            moved.repositoryPath(),
            moved.revision(),
            Instant.EPOCH);
    assertThrows(WorkspaceException.class, () -> service.open(movedProject, runId, workspace.id()));
    var secondWorkspace = service.create(project, new RunId("run-2"));
    assertFalse(workspace.path().equals(secondWorkspace.path()));
    assertEquals(
        registered.revision().value(), git(Path.of(secondWorkspace.path()), "rev-parse", "HEAD"));
    assertFalse(Files.exists(Path.of(secondWorkspace.path()).resolve("second.txt")));
    assertTrue(git(source, "status", "--porcelain").isEmpty());

    service.cleanup(project, runId, workspace.id());
    assertFalse(Files.exists(Path.of(workspace.path())));
    assertTrue(Files.exists(Path.of(secondWorkspace.path())));
    service.cleanup(project, new RunId("run-2"), secondWorkspace.id());
    service.cleanup(project, runId, workspace.id());
  }

  @Test
  void inspectsDirtyRepositoryWithoutRequiringJavaOrCompilation() throws IOException {
    var dirty = createRepository(temporaryDirectory.resolve("dirty"));
    Files.writeString(dirty.resolve("tracked.txt"), "changed");
    var inspection = service().inspect(new RepositoryLocation(dirty.toString()), null);
    assertFalse(inspection.clean());
    assertFalse(inspection.capabilities().contains("MAVEN_VERIFICATION"));
    assertTrue(inspection.capabilities().contains("SOURCE_BROWSING"));
    assertTrue(inspection.warnings().contains("UNCOMMITTED_CHANGES_EXCLUDED"));
    assertTrue(inspection.warnings().contains("JAVA_SEMANTIC_ANALYSIS_UNAVAILABLE"));
    assertEquals(
        inspection.revision(),
        service().register(new RepositoryLocation(dirty.toString()), null).revision());
  }

  @Test
  void snapshotsPlainLocalDirectoryWithoutModifyingOrRequiringGit() throws IOException {
    var source = temporaryDirectory.resolve("plain-project");
    Files.createDirectories(source.resolve("src"));
    Files.writeString(source.resolve("src/app.py"), "print('legacy')\n");
    var service = service();

    var inspection = service.inspect(new RepositoryLocation(source.toString()), "HEAD");

    assertEquals("LOCAL_DIRECTORY", inspection.sourceKind());
    assertEquals(List.of("PYTHON"), inspection.technologies());
    assertTrue(inspection.source().startsWith("directory://"));
    assertTrue(inspection.warnings().contains("DIRECTORY_SNAPSHOT_CREATED"));
    assertFalse(Files.exists(source.resolve(".git")));

    var registered =
        service.register(
            new RepositoryLocation(inspection.source()), inspection.revision().value());
    var project =
        new Project(
            new ProjectId("project-directory"),
            new RepositoryLocation(inspection.source()),
            registered.repositoryPath(),
            registered.revision(),
            Instant.EPOCH);
    var workspace = service.create(project, new RunId("run-directory"));
    assertEquals(
        "print('legacy')\n", Files.readString(Path.of(workspace.path()).resolve("src/app.py")));
    service.cleanup(project, new RunId("run-directory"), workspace.id());

    var uploaded =
        service.inspectDirectory(
            "selected-folder",
            List.of(new DirectorySnapshotFile("README.txt", "offline\n".getBytes())));
    assertEquals("LOCAL_DIRECTORY", uploaded.sourceKind());
    assertEquals("offline", git(Path.of(uploaded.repositoryPath()), "show", "HEAD:README.txt"));
  }

  @Test
  void rejectsUnsupportedOrMissingLocalRepositories() throws IOException {

    var submodules = createRepository(temporaryDirectory.resolve("submodules"));
    Files.writeString(submodules.resolve(".gitmodules"), "[submodule \"x\"]\n");
    git(submodules, "add", ".gitmodules");
    git(submodules, "commit", "-m", "submodule metadata");
    assertThrows(
        WorkspaceException.class,
        () -> service().register(new RepositoryLocation(submodules.toString()), null));

    var lfs = createRepository(temporaryDirectory.resolve("lfs"));
    Files.writeString(lfs.resolve(".gitattributes"), "*.bin filter=lfs diff=lfs merge=lfs\n");
    git(lfs, "add", ".gitattributes");
    git(lfs, "commit", "-m", "lfs metadata");
    assertThrows(
        WorkspaceException.class,
        () -> service().register(new RepositoryLocation(lfs.toString()), null));
    assertThrows(
        WorkspaceException.class,
        () ->
            service()
                .register(
                    new RepositoryLocation(temporaryDirectory.resolve("missing").toString()),
                    null));
  }

  @Test
  void validatesRevisionIdsOwnershipAndMarkerBeforeMutation() throws IOException {
    var source = createRepository(temporaryDirectory.resolve("safe"));
    var service = service();
    var registered = service.register(new RepositoryLocation(source.toString()), null);
    var project =
        new Project(
            new ProjectId("project"),
            new RepositoryLocation(source.toString()),
            registered.repositoryPath(),
            registered.revision(),
            Instant.EPOCH);
    assertThrows(
        WorkspaceException.class,
        () -> service.register(new RepositoryLocation(source.toString()), "-unsafe"));
    assertThrows(WorkspaceException.class, () -> service.create(project, new RunId("../escape")));

    var workspace = service.create(project, new RunId("run-marker"));
    assertThrows(
        WorkspaceException.class,
        () ->
            service.open(
                project, new RunId("another-run"), new WorkspaceId(workspace.id().value())));
    assertThrows(
        WorkspaceException.class,
        () -> service.open(project, new RunId("missing-run"), new WorkspaceId("missing-run")));
    assertThrows(
        WorkspaceException.class,
        () ->
            service.cleanup(
                project, new RunId("another-run"), new WorkspaceId(workspace.id().value())));
    Files.writeString(Path.of(workspace.path()).resolve(".legacypilot-workspace"), "wrong\n");
    assertThrows(
        WorkspaceException.class,
        () -> service.open(project, new RunId("run-marker"), workspace.id()));
    assertThrows(
        WorkspaceException.class,
        () -> service.cleanup(project, new RunId("run-marker"), workspace.id()));
  }

  private GitWorkspaceService service() {
    return new GitWorkspaceService(
        temporaryDirectory.resolve("managed"), Duration.ofSeconds(10), 32_768);
  }

  private static Path createRepository(Path path) throws IOException {
    Files.createDirectories(path);
    git(path, "init", "--initial-branch=main");
    git(path, "config", "user.name", "LegacyPilot Test");
    git(path, "config", "user.email", "test@legacypilot.invalid");
    Files.writeString(path.resolve("tracked.txt"), "initial\n");
    git(path, "add", "tracked.txt");
    git(path, "commit", "-m", "initial");
    return path;
  }

  private static String git(Path directory, String... arguments) {
    var command = new java.util.ArrayList<String>();
    command.add("git");
    command.addAll(List.of(arguments));
    try {
      var process = new ProcessBuilder(command).directory(directory.toFile()).start();
      var output =
          new String(
              process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      var error =
          new String(
              process.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      if (process.waitFor() != 0) {
        throw new IllegalStateException(error);
      }
      return output.strip();
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }
}

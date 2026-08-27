package io.legacypilot.workspace;

import io.legacypilot.application.port.RegisteredRepository;
import io.legacypilot.application.port.WorkspaceDescriptor;
import io.legacypilot.application.port.WorkspaceService;
import io.legacypilot.domain.project.GitRevision;
import io.legacypilot.domain.project.Project;
import io.legacypilot.domain.project.RepositoryLocation;
import io.legacypilot.domain.run.RunId;
import io.legacypilot.domain.run.WorkspaceId;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class GitWorkspaceService implements WorkspaceService {

  private static final String MARKER_FILE = ".legacypilot-workspace";
  private static final String SAFE_ID = "[A-Za-z0-9._-]+";

  private final Path managedRoot;
  private final Path repositoryCache;
  private final Path worktreeRoot;
  private final GitCommandRunner git;

  public GitWorkspaceService(Path managedRoot, Duration commandTimeout, int maxOutputCharacters) {
    this.managedRoot = managedRoot.toAbsolutePath().normalize();
    this.repositoryCache = this.managedRoot.resolve("repositories");
    this.worktreeRoot = this.managedRoot.resolve("worktrees");
    this.git = new GitCommandRunner(commandTimeout, maxOutputCharacters);
    createDirectories(repositoryCache);
    createDirectories(worktreeRoot);
  }

  @Override
  public RegisteredRepository register(RepositoryLocation location, String requestedRevision) {
    Objects.requireNonNull(location, "location must not be null");
    var source = location.value();
    return isHttpUrl(source)
        ? registerPublicRepository(source, requestedRevision)
        : registerLocalRepository(source, requestedRevision);
  }

  @Override
  public WorkspaceDescriptor create(Project project, RunId runId) {
    requireSafeId(runId.value());
    var workspaceId = new WorkspaceId(runId.value());
    var workspacePath = managedChild(worktreeRoot, workspaceId.value());
    if (Files.exists(workspacePath)) {
      verifyMarker(workspacePath, runId);
      var actualRevision =
          git.run(workspacePath, List.of("rev-parse", "--verify", "HEAD^{commit}"));
      if (!actualRevision.equalsIgnoreCase(project.baseRevision().value())) {
        throw new WorkspaceException("Existing workspace is pinned to a different revision");
      }
      return new WorkspaceDescriptor(workspaceId, workspacePath.toString());
    }

    var repository = Path.of(project.repositoryPath()).toAbsolutePath().normalize();
    git.run(
        null,
        List.of(
            "-C",
            repository.toString(),
            "worktree",
            "add",
            "--detach",
            workspacePath.toString(),
            project.baseRevision().value()));
    try {
      Files.writeString(
          workspacePath.resolve(MARKER_FILE),
          runId.value() + System.lineSeparator(),
          StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new WorkspaceException("Unable to create workspace marker", exception);
    }
    return new WorkspaceDescriptor(workspaceId, workspacePath.toString());
  }

  @Override
  public void cleanup(Project project, RunId runId, WorkspaceId workspaceId) {
    if (!workspaceId.value().equals(runId.value())) {
      throw new WorkspaceException("Workspace does not belong to the requested run");
    }
    requireSafeId(workspaceId.value());
    var workspacePath = managedChild(worktreeRoot, workspaceId.value());
    if (Files.notExists(workspacePath)) {
      return;
    }
    verifyMarker(workspacePath, runId);
    var repository = Path.of(project.repositoryPath()).toAbsolutePath().normalize();
    git.run(
        null,
        List.of(
            "-C",
            repository.toString(),
            "worktree",
            "remove",
            "--force",
            workspacePath.toString()));
    git.run(null, List.of("-C", repository.toString(), "worktree", "prune"));
  }

  private RegisteredRepository registerLocalRepository(String source, String requestedRevision) {
    try {
      var requestedPath = Path.of(source).toAbsolutePath().normalize().toRealPath();
      var repositoryRoot =
          Path.of(git.run(requestedPath, List.of("rev-parse", "--show-toplevel"))).toRealPath();
      var status = git.run(repositoryRoot, List.of("status", "--porcelain"));
      if (!status.isEmpty()) {
        throw new WorkspaceException("Local repository has uncommitted changes");
      }
      rejectUnsupportedRepositoryFeatures(repositoryRoot);
      var revision = resolveRevision(repositoryRoot, requestedRevision);
      return new RegisteredRepository(repositoryRoot.toString(), revision);
    } catch (IOException exception) {
      throw new WorkspaceException(
          "Local repository does not exist or is not accessible", exception);
    }
  }

  private RegisteredRepository registerPublicRepository(String source, String requestedRevision) {
    var uri = URI.create(source);
    if (uri.getUserInfo() != null
        || !("http".equalsIgnoreCase(uri.getScheme())
            || "https".equalsIgnoreCase(uri.getScheme()))) {
      throw new WorkspaceException(
          "Only public HTTP(S) Git URLs without credentials are supported");
    }
    var repository = managedChild(repositoryCache, sha256(source) + ".git");
    if (Files.notExists(repository)) {
      git.run(null, List.of("clone", "--mirror", "--", source, repository.toString()));
    } else {
      git.run(null, List.of("-C", repository.toString(), "remote", "update", "--prune"));
    }
    return new RegisteredRepository(
        repository.toString(), resolveRevision(repository, requestedRevision));
  }

  private GitRevision resolveRevision(Path repository, String requestedRevision) {
    var reference =
        requestedRevision == null || requestedRevision.isBlank() ? "HEAD" : requestedRevision;
    if (reference.startsWith("-")) {
      throw new WorkspaceException("Git revision must not start with '-'");
    }
    return new GitRevision(
        git.run(
            null,
            List.of(
                "-C", repository.toString(), "rev-parse", "--verify", reference + "^{commit}")));
  }

  private void rejectUnsupportedRepositoryFeatures(Path repository) throws IOException {
    if (Files.exists(repository.resolve(".gitmodules"))) {
      throw new WorkspaceException("Git submodules are not supported in this milestone");
    }
    var attributes = repository.resolve(".gitattributes");
    if (Files.exists(attributes)
        && Files.readString(attributes, StandardCharsets.UTF_8).contains("filter=lfs")) {
      throw new WorkspaceException("Git LFS is not supported in this milestone");
    }
  }

  private void verifyMarker(Path workspacePath, RunId runId) {
    try {
      var realRoot = worktreeRoot.toRealPath();
      var realWorkspace = workspacePath.toRealPath();
      if (!realWorkspace.startsWith(realRoot)) {
        throw new WorkspaceException("Workspace resolves outside the managed root");
      }
      var marker = realWorkspace.resolve(MARKER_FILE);
      if (!Files.isRegularFile(marker)
          || !Files.readString(marker, StandardCharsets.UTF_8).strip().equals(runId.value())) {
        throw new WorkspaceException("Workspace marker is missing or does not match the run");
      }
    } catch (IOException exception) {
      throw new WorkspaceException("Unable to verify workspace marker", exception);
    }
  }

  private static boolean isHttpUrl(String source) {
    return source.startsWith("https://") || source.startsWith("http://");
  }

  private static void requireSafeId(String value) {
    if (!value.matches(SAFE_ID)) {
      throw new WorkspaceException("Workspace id contains unsafe characters");
    }
  }

  private static Path managedChild(Path root, String child) {
    var result = root.resolve(child).normalize();
    if (!root.equals(result.getParent())) {
      throw new WorkspaceException("Managed path escapes its root");
    }
    return result;
  }

  private static void createDirectories(Path path) {
    try {
      Files.createDirectories(path);
    } catch (IOException exception) {
      throw new WorkspaceException("Unable to create managed directory: " + path, exception);
    }
  }

  private static String sha256(String value) {
    try {
      var digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }
}

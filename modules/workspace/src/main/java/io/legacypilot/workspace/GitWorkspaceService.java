package io.legacypilot.workspace;

import io.legacypilot.application.port.DirectorySnapshotFile;
import io.legacypilot.application.port.RegisteredRepository;
import io.legacypilot.application.port.RepositoryInspection;
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
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class GitWorkspaceService implements WorkspaceService {

  private static final String MARKER_FILE = ".legacypilot-workspace";
  private static final String SAFE_ID = "[A-Za-z0-9._-]+";
  private static final String DIRECTORY_SOURCE_PREFIX = "directory://";
  private static final int MAX_DIRECTORY_FILES = 20_000;
  private static final long MAX_DIRECTORY_BYTES = 256L * 1024 * 1024;

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
  public RepositoryInspection inspect(RepositoryLocation location, String requestedRevision) {
    Objects.requireNonNull(location, "location must not be null");
    var source = location.value();
    if (source.startsWith(DIRECTORY_SOURCE_PREFIX)) {
      return inspectManagedDirectory(source, requestedRevision);
    }
    return isHttpUrl(source)
        ? inspectPublicRepository(source, requestedRevision)
        : inspectLocalSource(source, requestedRevision);
  }

  @Override
  public RepositoryInspection inspectDirectory(
      String directoryName, List<DirectorySnapshotFile> files) {
    Objects.requireNonNull(files, "files must not be null");
    return createDirectorySnapshot(directoryName, files, null);
  }

  @Override
  public RegisteredRepository register(RepositoryLocation location, String requestedRevision) {
    var inspection = inspect(location, requestedRevision);
    return new RegisteredRepository(inspection.repositoryPath(), inspection.revision());
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
  public WorkspaceDescriptor open(Project project, RunId runId, WorkspaceId workspaceId) {
    Objects.requireNonNull(project, "project must not be null");
    Objects.requireNonNull(runId, "run id must not be null");
    Objects.requireNonNull(workspaceId, "workspace id must not be null");
    if (!workspaceId.value().equals(runId.value())) {
      throw new WorkspaceException("Workspace does not belong to the requested run");
    }
    requireSafeId(workspaceId.value());
    var workspacePath = managedChild(worktreeRoot, workspaceId.value());
    if (Files.notExists(workspacePath)) {
      throw new WorkspaceException("Managed workspace does not exist");
    }
    verifyMarker(workspacePath, runId);
    var actualRevision = git.run(workspacePath, List.of("rev-parse", "--verify", "HEAD^{commit}"));
    if (!actualRevision.equalsIgnoreCase(project.baseRevision().value())) {
      throw new WorkspaceException("Existing workspace is pinned to a different revision");
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

  private RepositoryInspection inspectLocalSource(String source, String requestedRevision) {
    try {
      var requestedPath = Path.of(source).toAbsolutePath().normalize().toRealPath();
      if (!Files.isDirectory(requestedPath)) {
        throw new WorkspaceException("Local project path must be a directory");
      }
      Path repositoryRoot;
      try {
        repositoryRoot =
            Path.of(git.run(requestedPath, List.of("rev-parse", "--show-toplevel"))).toRealPath();
      } catch (WorkspaceException exception) {
        var fileName = requestedPath.getFileName();
        return createDirectorySnapshot(
            fileName == null ? "local-project" : fileName.toString(),
            readDirectory(requestedPath),
            requestedRevision);
      }
      return inspectLocalRepository(source, repositoryRoot, requestedRevision);
    } catch (IOException exception) {
      throw new WorkspaceException("Local project does not exist or is not accessible", exception);
    }
  }

  private RepositoryInspection inspectLocalRepository(
      String source, Path repositoryRoot, String requestedRevision) throws IOException {
    rejectUnsupportedRepositoryFeatures(repositoryRoot);
    var revision = resolveRevision(repositoryRoot, requestedRevision);
    var clean = git.run(repositoryRoot, List.of("status", "--porcelain")).isEmpty();
    return inspectTree(source, repositoryRoot, revision, "LOCAL_GIT", clean, List.of());
  }

  private RepositoryInspection inspectPublicRepository(String source, String requestedRevision) {
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
    var revision = resolveRevision(repository, requestedRevision);
    return inspectTree(source, repository, revision, "PUBLIC_HTTP", true, List.of());
  }

  private RepositoryInspection inspectTree(
      String source,
      Path repository,
      GitRevision revision,
      String sourceKind,
      boolean clean,
      List<String> initialWarnings) {
    var tree =
        git.run(
            null,
            List.of("-C", repository.toString(), "ls-tree", "-r", "--name-only", revision.value()));
    var paths = tree.lines().toList();
    var technologies = new java.util.ArrayList<String>();
    if (paths.stream().anyMatch(path -> path.endsWith(".java"))) {
      technologies.add("JAVA");
    }
    if (paths.stream().anyMatch(path -> path.endsWith(".kt") || path.endsWith(".kts"))) {
      technologies.add("KOTLIN");
    }
    if (paths.stream().anyMatch(path -> path.endsWith(".ts") || path.endsWith(".tsx"))) {
      technologies.add("TYPESCRIPT");
    } else if (paths.stream().anyMatch(path -> path.endsWith(".js") || path.endsWith(".jsx"))) {
      technologies.add("JAVASCRIPT");
    }
    if (paths.stream().anyMatch(path -> path.endsWith(".py"))) {
      technologies.add("PYTHON");
    }
    if (paths.stream().anyMatch(path -> path.endsWith(".go"))) {
      technologies.add("GO");
    }
    if (paths.stream().anyMatch(path -> path.endsWith(".rs"))) {
      technologies.add("RUST");
    }
    if (technologies.isEmpty()) {
      technologies.add("OTHER");
    }

    var hasJava = technologies.contains("JAVA");
    var hasMaven =
        paths.stream().anyMatch(path -> path.equals("pom.xml") || path.endsWith("/pom.xml"));
    var hasGradle =
        paths.stream()
            .anyMatch(
                path ->
                    path.equals("build.gradle")
                        || path.equals("build.gradle.kts")
                        || path.endsWith("/build.gradle")
                        || path.endsWith("/build.gradle.kts"));
    var capabilities = new java.util.ArrayList<String>();
    capabilities.add("SOURCE_BROWSING");
    capabilities.add("TEXT_RETRIEVAL");
    if (hasJava) {
      capabilities.add("JAVA_SEMANTIC_ANALYSIS");
    }
    if (hasMaven) {
      capabilities.add("MAVEN_VERIFICATION");
    }

    var warnings = new java.util.ArrayList<>(initialWarnings);
    if (!clean) {
      warnings.add("UNCOMMITTED_CHANGES_EXCLUDED");
    }
    if (!hasJava) {
      warnings.add("JAVA_SEMANTIC_ANALYSIS_UNAVAILABLE");
    }
    if (!hasMaven) {
      warnings.add("MAVEN_VERIFICATION_UNAVAILABLE");
    }
    if (hasGradle) {
      warnings.add("GRADLE_DETECTED_BUT_VERIFICATION_UNAVAILABLE");
    }
    if (tree.endsWith("[output truncated]")) {
      warnings.add("TECHNOLOGY_SCAN_TRUNCATED");
    }
    return new RepositoryInspection(
        source,
        repository.toString(),
        revision,
        sourceKind,
        clean,
        technologies,
        capabilities,
        warnings);
  }

  private synchronized RepositoryInspection createDirectorySnapshot(
      String directoryName, List<DirectorySnapshotFile> inputFiles, String requestedRevision) {
    if (requestedRevision != null
        && !requestedRevision.isBlank()
        && !"HEAD".equalsIgnoreCase(requestedRevision)) {
      throw new WorkspaceException("Plain directories do not have branches, tags, or commits");
    }
    var files = normalizeSnapshotFiles(inputFiles);
    var digest = directoryDigest(files);
    var safeName = safeDirectoryName(directoryName);
    var source = DIRECTORY_SOURCE_PREFIX + digest + "/" + safeName;
    var repository = managedChild(repositoryCache, "directory-" + digest);
    if (Files.notExists(repository)) {
      materializeDirectoryRepository(repository, files);
    }
    var revision = resolveRevision(repository, "HEAD");
    return inspectTree(
        source,
        repository,
        revision,
        "LOCAL_DIRECTORY",
        true,
        List.of("DIRECTORY_SNAPSHOT_CREATED", "GIT_HISTORY_UNAVAILABLE"));
  }

  private RepositoryInspection inspectManagedDirectory(String source, String requestedRevision) {
    var remainder = source.substring(DIRECTORY_SOURCE_PREFIX.length());
    var slash = remainder.indexOf('/');
    var digest = slash < 0 ? remainder : remainder.substring(0, slash);
    if (!digest.matches("[0-9a-f]{64}")) {
      throw new WorkspaceException("Managed directory snapshot id is invalid");
    }
    var repository = managedChild(repositoryCache, "directory-" + digest);
    if (Files.notExists(repository)) {
      throw new WorkspaceException("Managed directory snapshot no longer exists");
    }
    var revision = resolveRevision(repository, requestedRevision);
    return inspectTree(
        source,
        repository,
        revision,
        "LOCAL_DIRECTORY",
        true,
        List.of("DIRECTORY_SNAPSHOT_CREATED", "GIT_HISTORY_UNAVAILABLE"));
  }

  private List<DirectorySnapshotFile> readDirectory(Path root) throws IOException {
    var files = new ArrayList<DirectorySnapshotFile>();
    try (var paths = Files.walk(root)) {
      var candidates = paths.filter(path -> !path.equals(root)).sorted().toList();
      for (var path : candidates) {
        var relative = root.relativize(path);
        if (containsGitMetadata(relative)) {
          continue;
        }
        if (Files.isSymbolicLink(path)) {
          throw new WorkspaceException("Symbolic links are not supported in directory snapshots");
        }
        if (Files.isRegularFile(path)) {
          files.add(new DirectorySnapshotFile(relative.toString(), Files.readAllBytes(path)));
        }
      }
    }
    return files;
  }

  private List<DirectorySnapshotFile> normalizeSnapshotFiles(
      List<DirectorySnapshotFile> inputFiles) {
    if (inputFiles.size() > MAX_DIRECTORY_FILES) {
      throw new WorkspaceException("Directory contains too many files");
    }
    var normalized = new ArrayList<DirectorySnapshotFile>();
    long totalBytes = 0;
    for (var file : inputFiles) {
      var relative = Path.of(file.relativePath().replace('\\', '/')).normalize();
      if (relative.isAbsolute() || relative.getNameCount() == 0 || relative.startsWith("..")) {
        throw new WorkspaceException("Directory file path escapes the selected root");
      }
      if (containsGitMetadata(relative)) {
        continue;
      }
      var content = file.content();
      totalBytes += content.length;
      if (totalBytes > MAX_DIRECTORY_BYTES) {
        throw new WorkspaceException("Directory snapshot exceeds 256 MiB");
      }
      normalized.add(new DirectorySnapshotFile(relative.toString().replace('\\', '/'), content));
    }
    normalized.sort(Comparator.comparing(DirectorySnapshotFile::relativePath));
    for (int index = 1; index < normalized.size(); index++) {
      if (normalized.get(index - 1).relativePath().equals(normalized.get(index).relativePath())) {
        throw new WorkspaceException("Directory contains duplicate file paths");
      }
    }
    return List.copyOf(normalized);
  }

  private void materializeDirectoryRepository(Path repository, List<DirectorySnapshotFile> files) {
    var temporary = managedChild(repositoryCache, ".directory-" + UUID.randomUUID().toString());
    try {
      Files.createDirectories(temporary);
      for (var file : files) {
        var target = temporary.resolve(file.relativePath()).normalize();
        if (!target.startsWith(temporary)) {
          throw new WorkspaceException("Directory file path escapes the managed snapshot");
        }
        var parent = target.getParent();
        if (parent == null) {
          throw new WorkspaceException("Directory file path has no managed parent");
        }
        Files.createDirectories(parent);
        Files.write(target, file.content());
      }
      git.run(temporary, List.of("init", "--quiet"));
      git.run(temporary, List.of("add", "--all"));
      git.run(
          temporary,
          List.of(
              "-c",
              "user.name=LegacyPilot",
              "-c",
              "user.email=snapshot@legacypilot.local",
              "commit",
              "--quiet",
              "--allow-empty",
              "--no-gpg-sign",
              "-m",
              "LegacyPilot directory snapshot"));
      Files.move(temporary, repository, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException exception) {
      throw new WorkspaceException("Unable to create managed directory snapshot", exception);
    } finally {
      deleteRecursively(temporary);
    }
  }

  private static String directoryDigest(List<DirectorySnapshotFile> files) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (var file : files) {
        digest.update(file.relativePath().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(file.content());
        digest.update((byte) 0);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private static boolean containsGitMetadata(Path path) {
    for (var segment : path) {
      if (".git".equals(segment.toString())) {
        return true;
      }
    }
    return false;
  }

  private static String safeDirectoryName(String value) {
    var candidate = value == null ? "local-project" : value.strip();
    candidate = candidate.replaceAll("[^A-Za-z0-9._-]", "_");
    if (candidate.isBlank()) {
      return "local-project";
    }
    return candidate.substring(0, Math.min(candidate.length(), 80));
  }

  private static void deleteRecursively(Path root) {
    if (Files.notExists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(GitWorkspaceService::deletePath);
    } catch (IOException exception) {
      throw new WorkspaceException("Unable to clean temporary directory snapshot", exception);
    }
  }

  private static void deletePath(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException exception) {
      throw new WorkspaceException("Unable to clean temporary directory snapshot", exception);
    }
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

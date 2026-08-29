package io.legacypilot.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class WorkspaceIntegrityGuard {
  private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(".git", ".legacy-pilot", "target");

  public Snapshot capture(Path workspace) {
    return new Snapshot(fileDigests(workspace));
  }

  public Verification verify(Snapshot baseline, Path workspace, EvalTask task) {
    Objects.requireNonNull(baseline);
    Objects.requireNonNull(task);
    var current = fileDigests(workspace);
    var paths = new TreeSet<String>();
    paths.addAll(baseline.fileDigests().keySet());
    paths.addAll(current.keySet());
    var changed = new ArrayList<String>();
    var violations = new ArrayList<String>();
    for (var path : paths) {
      if (!Objects.equals(baseline.fileDigests().get(path), current.get(path))) {
        changed.add(path);
        if (!task.allowedFiles().contains(path)) {
          violations.add(path + ": change is outside allowedFiles");
        }
        if (task.forbiddenFiles().contains(path)) {
          violations.add(path + ": forbidden file changed");
        }
      }
    }
    for (var expected : task.expectedFiles()) {
      if (!current.containsKey(expected)) {
        violations.add(expected + ": expected production file is missing");
      }
    }
    return new Verification(changed, violations);
  }

  private static Map<String, String> fileDigests(Path workspace) {
    var root = Objects.requireNonNull(workspace).toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) {
      throw new IllegalArgumentException("eval workspace is unavailable");
    }
    var result = new HashMap<String, String>();
    try (var paths = Files.walk(root)) {
      for (var path : paths.filter(value -> !excluded(root, value)).toList()) {
        if (Files.isSymbolicLink(path)) {
          throw new IllegalArgumentException("eval workspace contains a symbolic link");
        }
        if (Files.isRegularFile(path)) {
          result.put(normalize(root.relativize(path)), sha256(path));
        }
      }
      return Map.copyOf(result);
    } catch (IOException exception) {
      throw new IllegalStateException("unable to inspect eval workspace", exception);
    }
  }

  private static boolean excluded(Path root, Path path) {
    for (var component : root.relativize(path.toAbsolutePath().normalize())) {
      if (EXCLUDED_DIRECTORIES.contains(component.toString())) {
        return true;
      }
    }
    return false;
  }

  private static String normalize(Path path) {
    return path.toString().replace('\\', '/');
  }

  private static String sha256(Path path) throws IOException {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(path)) {
        var buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
          digest.update(buffer, 0, read);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public record Snapshot(Map<String, String> fileDigests) {
    public Snapshot {
      fileDigests = Map.copyOf(Objects.requireNonNull(fileDigests));
    }
  }

  public record Verification(List<String> changedFiles, List<String> violations) {
    public Verification {
      changedFiles = List.copyOf(Objects.requireNonNull(changedFiles));
      violations = List.copyOf(Objects.requireNonNull(violations));
    }

    public boolean successful() {
      return violations.isEmpty();
    }
  }
}

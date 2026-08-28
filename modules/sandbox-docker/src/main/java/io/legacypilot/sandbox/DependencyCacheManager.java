package io.legacypilot.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class DependencyCacheManager {
  private static final List<String> INPUTS =
      List.of("pom.xml", ".mvn/extensions.xml", ".mvn/maven.config", "mvnw");
  private final Path root;
  private final long maximumBytes;

  public DependencyCacheManager(Path root, long maximumBytes) {
    this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
    if (maximumBytes < 1024 * 1024) {
      throw new IllegalArgumentException("dependency cache limit is too small");
    }
    this.maximumBytes = maximumBytes;
    try {
      Files.createDirectories(this.root);
      if (Files.isSymbolicLink(this.root)) {
        throw new IllegalArgumentException("dependency cache root must not be a symbolic link");
      }
    } catch (IOException exception) {
      throw new IllegalArgumentException("dependency cache root is unavailable", exception);
    }
  }

  public Path cacheFor(Path workspace) {
    var normalized = Objects.requireNonNull(workspace).toAbsolutePath().normalize();
    if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("workspace must be a real directory");
    }
    var cache = root.resolve(fingerprint(normalized));
    try {
      Files.createDirectories(cache);
      try {
        Files.setPosixFilePermissions(cache, PosixFilePermissions.fromString("rwx------"));
      } catch (UnsupportedOperationException ignored) {
        // Non-POSIX file systems still retain path and size validation.
      }
    } catch (IOException exception) {
      throw new IllegalStateException("unable to create dependency cache", exception);
    }
    validate(cache);
    return cache;
  }

  public void validate(Path cache) {
    var normalized = Objects.requireNonNull(cache).toAbsolutePath().normalize();
    if (!normalized.startsWith(root)
        || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(normalized)) {
      throw new IllegalArgumentException("dependency cache is outside the managed root");
    }
    try (var paths = Files.walk(normalized)) {
      var size =
          paths
              .peek(
                  path -> {
                    if (Files.isSymbolicLink(path)) {
                      throw new IllegalArgumentException(
                          "dependency cache contains a symbolic link");
                    }
                  })
              .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
              .mapToLong(DependencyCacheManager::size)
              .sum();
      if (size > maximumBytes) {
        throw new IllegalArgumentException("dependency cache exceeds its size limit");
      }
    } catch (IOException exception) {
      throw new IllegalStateException("unable to inspect dependency cache", exception);
    }
  }

  private static String fingerprint(Path workspace) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (var relative : INPUTS.stream().sorted(Comparator.naturalOrder()).toList()) {
        digest.update(relative.getBytes(StandardCharsets.UTF_8));
        var file = workspace.resolve(relative).normalize();
        if (file.startsWith(workspace) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
          digest.update(Files.readAllBytes(file));
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    } catch (IOException exception) {
      throw new IllegalArgumentException("unable to fingerprint Maven inputs", exception);
    }
  }

  private static long size(Path path) {
    try {
      return Files.size(path);
    } catch (IOException exception) {
      throw new IllegalStateException("unable to inspect dependency cache file", exception);
    }
  }
}

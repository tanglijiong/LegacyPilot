package io.legacypilot.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Objects;

public final class FixtureWorkspace implements AutoCloseable {
  private final Path root;

  private FixtureWorkspace(Path root) {
    this.root = root;
  }

  public static FixtureWorkspace copyOf(Path fixture) {
    var source = Objects.requireNonNull(fixture).toAbsolutePath().normalize();
    if (!Files.isDirectory(source)) {
      throw new IllegalArgumentException("fixture directory is unavailable");
    }
    try {
      var target = Files.createTempDirectory("legacy-pilot-eval-");
      try (var paths = Files.walk(source)) {
        for (var path : paths.toList()) {
          var destination = target.resolve(source.relativize(path).toString());
          if (Files.isDirectory(path)) {
            Files.createDirectories(destination);
          } else {
            Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
          }
        }
      }
      return new FixtureWorkspace(target);
    } catch (IOException exception) {
      throw new IllegalStateException("unable to isolate fixture", exception);
    }
  }

  public Path root() {
    return root;
  }

  public void overlay(Path source) {
    var overlay = Objects.requireNonNull(source).toAbsolutePath().normalize();
    try (var paths = Files.walk(overlay)) {
      for (var path : paths.toList()) {
        var destination = root.resolve(overlay.relativize(path).toString());
        if (Files.isDirectory(path)) {
          Files.createDirectories(destination);
        } else {
          Files.createDirectories(Objects.requireNonNull(destination.getParent()));
          Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    } catch (IOException exception) {
      throw new IllegalStateException("unable to apply reference overlay", exception);
    }
  }

  @Override
  public void close() {
    try (var paths = Files.walk(root)) {
      for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("unable to remove isolated fixture", exception);
    }
  }
}

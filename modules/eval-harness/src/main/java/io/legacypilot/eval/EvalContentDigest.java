package io.legacypilot.eval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class EvalContentDigest {
  private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(".git", ".legacy-pilot", "target");

  private EvalContentDigest() {}

  public static String fixtureSha256(Path fixtureRoot) {
    return treeSha256(fixtureRoot, List.of(fixtureRoot));
  }

  public static String datasetSha256(Path datasetRoot, List<String> taskIds) {
    var roots = taskIds.stream().map(datasetRoot.toAbsolutePath().normalize()::resolve).toList();
    return treeSha256(datasetRoot, roots);
  }

  private static String treeSha256(Path namingRoot, List<Path> contentRoots) {
    var normalizedRoot = namingRoot.toAbsolutePath().normalize();
    var digest = digest();
    try {
      var files = new ArrayList<Path>();
      for (var contentRoot : contentRoots) {
        try (var paths = Files.walk(contentRoot.toAbsolutePath().normalize())) {
          paths
              .filter(path -> !excluded(normalizedRoot, path))
              .filter(Files::isRegularFile)
              .forEach(files::add);
        }
      }
      files.sort(Comparator.comparing(path -> normalizedRoot.relativize(path).toString()));
      if (files.isEmpty()) {
        throw new IllegalArgumentException("eval content contains no files");
      }
      for (var file : files) {
        if (Files.isSymbolicLink(file)) {
          throw new IllegalArgumentException("eval content must not contain symbolic links");
        }
        update(digest, normalizedRoot.relativize(file).toString().replace('\\', '/'));
        digest.update((byte) 0);
        try (InputStream input = Files.newInputStream(file)) {
          input.transferTo(new java.security.DigestOutputStream(OutputStreamSink.INSTANCE, digest));
        }
        digest.update((byte) 0);
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (IOException exception) {
      throw new IllegalStateException("unable to hash eval content", exception);
    }
  }

  private static boolean excluded(Path root, Path path) {
    var relative = root.relativize(path.toAbsolutePath().normalize());
    for (var component : relative) {
      if (EXCLUDED_DIRECTORIES.contains(component.toString())) {
        return true;
      }
    }
    return false;
  }

  private static void update(MessageDigest digest, String value) {
    digest.update(value.getBytes(StandardCharsets.UTF_8));
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static final class OutputStreamSink extends java.io.OutputStream {
    private static final OutputStreamSink INSTANCE = new OutputStreamSink();

    @Override
    public void write(int value) {}

    @Override
    public void write(byte[] values, int offset, int length) {}
  }
}

package io.legacypilot.observability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** A source-backed claim emitted by a read-only agent task. */
public record SourceEvidence(
    String path,
    String symbol,
    int startLine,
    int endLine,
    String excerpt,
    String claim,
    String revision,
    String excerptSha256) {

  public SourceEvidence(
      String path,
      String symbol,
      int startLine,
      int endLine,
      String excerpt,
      String claim,
      String revision) {
    this(path, symbol, startLine, endLine, excerpt, claim, revision, null);
  }

  public SourceEvidence {
    Objects.requireNonNull(path, "evidence path is required");
    Objects.requireNonNull(symbol, "evidence symbol is required");
    Objects.requireNonNull(excerpt, "evidence excerpt is required");
    Objects.requireNonNull(claim, "evidence claim is required");
    Objects.requireNonNull(revision, "evidence revision is required");
    excerptSha256 =
        excerptSha256 == null || excerptSha256.isBlank() ? sha256(excerpt) : excerptSha256;
    if (path.isBlank()
        || symbol.isBlank()
        || excerpt.isBlank()
        || claim.isBlank()
        || revision.isBlank()
        || !excerptSha256.matches("[0-9a-f]{64}")
        || !excerptSha256.equals(sha256(excerpt))
        || startLine < 1
        || endLine < startLine) {
      throw new IllegalArgumentException("source evidence is invalid");
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }
}

package io.legacypilot.context;

import io.legacypilot.analysis.java.SourceSymbol;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

final class CandidateSupport {

  private CandidateSupport() {}

  static EvidenceCandidate candidate(
      SourceSymbol symbol, double score, RetrievalSource source, String reason) {
    return new EvidenceCandidate(
        referenceId(symbol.id()),
        symbol.id(),
        symbol.path(),
        symbol.range(),
        summary(symbol),
        score,
        java.util.Set.of(source),
        reason);
  }

  static String referenceId(String symbolId) {
    try {
      var bytes =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(symbolId.getBytes(StandardCharsets.UTF_8));
      return "ref-" + HexFormat.of().formatHex(bytes, 0, 8);
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  static String summary(SourceSymbol symbol) {
    var prefix = symbol.javadoc().isBlank() ? "" : symbol.javadoc() + " — ";
    var value = prefix + symbol.signature();
    return value.length() <= 500 ? value : value.substring(0, 500);
  }
}

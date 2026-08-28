package io.legacypilot.context;

import io.legacypilot.analysis.java.ProjectIndex;
import io.legacypilot.analysis.java.SourceSymbol;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class VectorIndexer {
  private final EmbeddingProvider embeddings;
  private final VectorStore store;

  public VectorIndexer(EmbeddingProvider embeddings, VectorStore store) {
    this.embeddings = Objects.requireNonNull(embeddings);
    this.store = Objects.requireNonNull(store);
  }

  public int synchronize(ProjectIndex index) {
    var entries = index.symbols().stream().map(symbol -> entry(index.revision(), symbol)).toList();
    if (entries.isEmpty()) {
      return store.deleteRevision(index.revision());
    }
    store.replaceRevision(index.revision(), entries.getFirst().model(), entries);
    return entries.size();
  }

  private VectorEntry entry(String revision, SourceSymbol symbol) {
    var text =
        symbol.qualifiedName()
            + " "
            + symbol.signature()
            + " "
            + symbol.javadoc()
            + " "
            + symbol.sourceText();
    var vector = embeddings.embed(text);
    return new VectorEntry(
        revision,
        vector.model(),
        digest(symbol.sourceText()),
        symbol.id(),
        symbol.path(),
        symbol.range(),
        CandidateSupport.summary(symbol),
        vector.values());
  }

  private static String digest(String content) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}

package io.legacypilot.context;

import io.legacypilot.analysis.java.ProjectIndex;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;

public final class Bm25Retriever implements Retriever {

  @Override
  public List<EvidenceCandidate> retrieve(ProjectIndex index, String query, int limit) {
    ExactSymbolRetriever.validate(query, limit);
    try (var analyzer = new StandardAnalyzer();
        var directory = new ByteBuffersDirectory()) {
      write(index, analyzer, directory);
      try (var reader = DirectoryReader.open(directory)) {
        var searcher = new IndexSearcher(reader);
        searcher.setSimilarity(new BM25Similarity());
        var parser =
            new MultiFieldQueryParser(
                new String[] {"name", "signature", "annotations", "content"}, analyzer);
        var parsed = parser.parse(QueryParser.escape(query.strip()));
        var hits = searcher.search(parsed, limit);
        var results = new ArrayList<EvidenceCandidate>();
        for (var hit : hits.scoreDocs) {
          var document = searcher.storedFields().document(hit.doc);
          index
              .symbol(document.get("id"))
              .ifPresent(
                  symbol ->
                      results.add(
                          CandidateSupport.candidate(
                              symbol,
                              Math.max(0.0001, hit.score),
                              RetrievalSource.BM25,
                              "Lucene BM25 match")));
        }
        return List.copyOf(results);
      }
    } catch (org.apache.lucene.queryparser.classic.ParseException exception) {
      throw new IllegalArgumentException("unable to parse retrieval query", exception);
    } catch (IOException exception) {
      throw new IllegalStateException("unable to build in-memory BM25 index", exception);
    }
  }

  private static void write(
      ProjectIndex index, StandardAnalyzer analyzer, ByteBuffersDirectory directory)
      throws IOException {
    var configuration = new IndexWriterConfig(analyzer).setSimilarity(new BM25Similarity());
    try (var writer = new IndexWriter(directory, configuration)) {
      for (var symbol : index.symbols()) {
        var document = new Document();
        document.add(new StringField("id", symbol.id(), Field.Store.YES));
        document.add(new TextField("name", symbol.simpleName(), Field.Store.NO));
        document.add(new TextField("signature", symbol.signature(), Field.Store.NO));
        document.add(
            new TextField("annotations", String.join(" ", symbol.annotations()), Field.Store.NO));
        document.add(
            new TextField(
                "content",
                symbol.qualifiedName() + " " + symbol.javadoc() + " " + symbol.sourceText(),
                Field.Store.NO));
        writer.addDocument(document);
      }
      writer.commit();
    }
  }
}

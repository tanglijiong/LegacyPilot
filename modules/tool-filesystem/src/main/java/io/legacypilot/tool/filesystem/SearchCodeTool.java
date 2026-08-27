package io.legacypilot.tool.filesystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.tool.spi.AgentTool;
import io.legacypilot.tool.spi.Idempotency;
import io.legacypilot.tool.spi.JsonSchemas;
import io.legacypilot.tool.spi.RiskLevel;
import io.legacypilot.tool.spi.ToolContext;
import io.legacypilot.tool.spi.ToolDescriptor;
import io.legacypilot.tool.spi.ToolErrorCode;
import io.legacypilot.tool.spi.ToolFailureException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

public final class SearchCodeTool implements AgentTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final long MAX_SEARCHABLE_FILE_BYTES = 2L * 1024 * 1024;
  private static final Set<String> TEXT_EXTENSIONS =
      Set.of("java", "kt", "xml", "yml", "yaml", "properties", "md", "txt", "json", "sql");

  private final ToolDescriptor descriptor;
  private final boolean wholeWord;

  public SearchCodeTool() {
    this("search_code", "Search source text using a bounded literal query.", false);
  }

  public static SearchCodeTool findReferences() {
    return new SearchCodeTool(
        "find_references", "Find whole-word textual references to a symbol.", true);
  }

  private SearchCodeTool(String name, String description, boolean wholeWord) {
    this.wholeWord = wholeWord;
    this.descriptor =
        new ToolDescriptor(
            name,
            description,
            JsonSchemas.parse(
                """
                {"type":"object","required":["query"],"additionalProperties":false,
                 "properties":{"query":{"type":"string","maxLength":256},
                 "maxMatches":{"type":"integer"}}}
                """),
            JsonSchemas.parse("{\"type\":\"object\"}"),
            RiskLevel.READ_ONLY,
            Idempotency.IDEMPOTENT,
            Duration.ofSeconds(10),
            8 * 1024,
            1024 * 1024,
            Set.of());
  }

  @Override
  public ToolDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public JsonNode execute(ToolContext context, JsonNode input) {
    var query = input.path("query").asText();
    if (query.isBlank()) {
      throw new ToolFailureException(ToolErrorCode.INVALID_INPUT_SCHEMA, "query must not be blank");
    }
    var maximum = Math.max(1, Math.min(500, input.path("maxMatches").asInt(100)));
    var matches = MAPPER.createArrayNode();
    try (var paths = Files.walk(context.workspaceRoot())) {
      var files = paths.filter(this::searchable).sorted().toList();
      outer:
      for (var file : files) {
        var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
          if (matches(lines.get(index), query)) {
            var match = matches.addObject();
            match.put("path", WorkspacePaths.relative(context.workspaceRoot(), file));
            match.put("line", index + 1);
            match.put("text", bounded(lines.get(index), 500));
            if (matches.size() >= maximum) {
              break outer;
            }
          }
        }
      }
      var output = MAPPER.createObjectNode();
      output.set("matches", matches);
      output.put("truncated", matches.size() >= maximum);
      return output;
    } catch (IOException exception) {
      throw new ToolFailureException(
          ToolErrorCode.COMMAND_FAILED, "Unable to search workspace", exception);
    }
  }

  private boolean searchable(Path path) {
    if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
      return false;
    }
    try {
      if (Files.size(path) > MAX_SEARCHABLE_FILE_BYTES) {
        return false;
      }
    } catch (IOException exception) {
      return false;
    }
    var fileName = path.getFileName();
    if (fileName == null) {
      return false;
    }
    var name = fileName.toString();
    var separator = name.lastIndexOf('.');
    return separator > 0
        && TEXT_EXTENSIONS.contains(name.substring(separator + 1).toLowerCase(Locale.ROOT));
  }

  private boolean matches(String line, String query) {
    if (!wholeWord) {
      return line.contains(query);
    }
    return java.util.regex.Pattern.compile(
            "(?<![A-Za-z0-9_$])" + java.util.regex.Pattern.quote(query) + "(?![A-Za-z0-9_$])")
        .matcher(line)
        .find();
  }

  private static String bounded(String value, int limit) {
    return value.length() <= limit ? value : value.substring(0, limit);
  }
}

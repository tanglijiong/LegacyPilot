package io.legacypilot.observability;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class SensitiveDataRedactor {

  private static final Set<String> SENSITIVE_KEYS =
      Set.of(
          "authorization", "api_key", "apikey", "password", "secret", "token", "connection_string");
  private static final List<Pattern> VALUE_PATTERNS =
      List.of(
          Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/-]+=*"),
          Pattern.compile("(?i)(api[_-]?key|password|secret|token)\\s*[=:]\\s*[^\\s,;]+"),
          Pattern.compile("(?i)jdbc:[^\\s]+"));

  private final int maximumLength;

  public SensitiveDataRedactor(int maximumLength) {
    if (maximumLength < 64) {
      throw new IllegalArgumentException("redacted value limit is too small");
    }
    this.maximumLength = maximumLength;
  }

  public String redact(String key, String value) {
    if (value == null) {
      return "";
    }
    if (isSensitiveKey(key)) {
      return "[REDACTED]";
    }
    var redacted = value;
    for (var pattern : VALUE_PATTERNS) {
      redacted = pattern.matcher(redacted).replaceAll("[REDACTED]");
    }
    return redacted.length() <= maximumLength
        ? redacted
        : redacted.substring(0, maximumLength) + "…[TRUNCATED]";
  }

  private static boolean isSensitiveKey(String key) {
    var normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
    return SENSITIVE_KEYS.stream().anyMatch(normalized::contains);
  }
}

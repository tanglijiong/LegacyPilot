package io.legacypilot.model.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.model.ModelErrorType;
import io.legacypilot.model.ModelException;
import io.legacypilot.model.ModelRequest;
import io.legacypilot.model.ModelUsage;
import io.legacypilot.model.RawModelClient;
import io.legacypilot.model.RawModelResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** OpenAI-compatible client restricted to a loopback HTTP model endpoint. */
public final class LocalHttpRawModelClient implements RawModelClient {
  private static final int MAXIMUM_RESPONSE_BYTES = 16 * 1024 * 1024;

  private final URI completionsEndpoint;
  private final ObjectMapper mapper;
  private final HttpClient http;

  public LocalHttpRawModelClient(String baseUrl, ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper);
    var base = validatedBaseUrl(baseUrl);
    this.completionsEndpoint = URI.create(base + "/chat/completions");
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public RawModelResponse complete(ModelRequest request) {
    var started = Instant.now();
    try {
      var payload = requestBody(request);
      var httpRequest =
          HttpRequest.newBuilder(completionsEndpoint)
              .timeout(request.timeout())
              .header("Content-Type", "application/json")
              .header("Accept", "application/json")
              .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
              .build();
      var response = http.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
      if (response.body().length > MAXIMUM_RESPONSE_BYTES) {
        throw invalid("Local model response exceeds the configured maximum");
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        var retryable = response.statusCode() == 429 || response.statusCode() >= 500;
        var type =
            response.statusCode() == 429
                ? ModelErrorType.RATE_LIMIT
                : retryable ? ModelErrorType.PROVIDER_UNAVAILABLE : ModelErrorType.INVALID_RESPONSE;
        throw new ModelException(
            type, "Local model returned HTTP " + response.statusCode(), retryable);
      }
      var json = mapper.readTree(response.body());
      var content = json.path("choices").path(0).path("message").path("content").asText("");
      if (content.isBlank()) {
        throw invalid("Local model returned empty content");
      }
      var usage = json.path("usage");
      var input =
          usage.path("prompt_tokens").canConvertToInt()
              ? usage.path("prompt_tokens").asInt()
              : estimate(request.systemPrompt() + request.userPrompt());
      var output =
          usage.path("completion_tokens").canConvertToInt()
              ? usage.path("completion_tokens").asInt()
              : estimate(content);
      return new RawModelResponse(
          content,
          new ModelUsage(input, output, BigDecimal.ZERO, request.model()),
          Duration.between(started, Instant.now()));
    } catch (HttpTimeoutException exception) {
      throw new ModelException(
          ModelErrorType.TIMEOUT, "Local model request timed out", true, exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ModelException(
          ModelErrorType.INTERNAL, "Local model request interrupted", true, exception);
    } catch (IOException exception) {
      throw new ModelException(
          ModelErrorType.PROVIDER_UNAVAILABLE,
          "Local model HTTP communication failed",
          true,
          exception);
    }
  }

  private byte[] requestBody(ModelRequest request) throws IOException {
    var system =
        request.systemPrompt() + "\nReturn JSON matching this schema: " + request.outputSchema();
    return mapper.writeValueAsBytes(
        Map.of(
            "model",
            request.model(),
            "temperature",
            request.temperature(),
            "max_tokens",
            actionOutput(request) ? 1200 : 1800,
            "stream",
            false,
            "response_format",
            Map.of("type", "json_object"),
            "messages",
            List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", request.userPrompt()))));
  }

  private static boolean actionOutput(ModelRequest request) {
    return request.outputSchema().has("oneOf");
  }

  private static String validatedBaseUrl(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("local model base URL is required");
    }
    var normalized = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    URI uri;
    try {
      uri = URI.create(normalized);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("local model base URL is invalid", exception);
    }
    var host = uri.getHost();
    if (!"http".equals(uri.getScheme())
        || host == null
        || !(host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1"))
        || uri.getPort() < 1
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null) {
      throw new IllegalArgumentException(
          "local model base URL must be an explicit loopback HTTP endpoint");
    }
    return normalized;
  }

  private static ModelException invalid(String message) {
    return new ModelException(ModelErrorType.INVALID_RESPONSE, message, false);
  }

  private static int estimate(String text) {
    return Math.max(1, (text.codePointCount(0, text.length()) + 3) / 4);
  }
}

package io.legacypilot.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

public final class ReplayModelGateway implements ModelGateway {

  private final Map<String, Object> responses;
  private final ObjectMapper mapper;

  public ReplayModelGateway(Map<String, ?> responses, ObjectMapper mapper) {
    this.responses = Map.copyOf(responses);
    this.mapper = Objects.requireNonNull(mapper);
  }

  @Override
  public <T> ModelResult<T> generate(ModelRequest request, Class<T> responseType) {
    var value = responses.get(key(request));
    if (value == null) {
      throw new ModelException(ModelErrorType.INVALID_RESPONSE, "Replay response not found", false);
    }
    return new ModelResult<>(
        mapper.convertValue(value, responseType), ModelUsage.NONE, Duration.ZERO, 0);
  }

  public static String key(ModelRequest request) {
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      var value =
          request.systemPrompt() + '\u0000' + request.userPrompt() + '\u0000' + request.model();
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}

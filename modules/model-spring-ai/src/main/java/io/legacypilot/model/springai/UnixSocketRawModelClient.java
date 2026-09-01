package io.legacypilot.model.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.model.ModelErrorType;
import io.legacypilot.model.ModelException;
import io.legacypilot.model.ModelRequest;
import io.legacypilot.model.ModelUsage;
import io.legacypilot.model.RawModelClient;
import io.legacypilot.model.RawModelResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** OpenAI-compatible chat client that can only reach a model through a Unix-domain socket. */
public final class UnixSocketRawModelClient implements RawModelClient {
  private static final int MAXIMUM_RESPONSE_BYTES = 16 * 1024 * 1024;
  private final Path socket;
  private final ObjectMapper mapper;

  public UnixSocketRawModelClient(Path socket, ObjectMapper mapper) {
    this.socket = Objects.requireNonNull(socket).toAbsolutePath().normalize();
    this.mapper = Objects.requireNonNull(mapper);
    var fileName = this.socket.getFileName();
    if (fileName == null || !fileName.toString().matches("[A-Za-z0-9._-]+")) {
      throw new IllegalArgumentException("model socket path is invalid");
    }
  }

  @Override
  public RawModelResponse complete(ModelRequest request) {
    if (!Files.exists(socket) || Files.isSymbolicLink(socket)) {
      throw new ModelException(
          ModelErrorType.PROVIDER_UNAVAILABLE, "Approved model socket is unavailable", true);
    }
    var started = Instant.now();
    var active = new AtomicReference<SocketChannel>();
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    var future = executor.submit(() -> exchange(request, active));
    try {
      var content = future.get(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
      return new RawModelResponse(
          content,
          new ModelUsage(
              estimate(request.systemPrompt() + request.userPrompt()),
              estimate(content),
              BigDecimal.ZERO,
              request.model()),
          Duration.between(started, Instant.now()));
    } catch (TimeoutException exception) {
      close(active.get());
      future.cancel(true);
      throw new ModelException(
          ModelErrorType.TIMEOUT, "Local model request timed out", true, exception);
    } catch (InterruptedException exception) {
      close(active.get());
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new ModelException(
          ModelErrorType.INTERNAL, "Local model request interrupted", true, exception);
    } catch (ExecutionException exception) {
      var cause = exception.getCause();
      if (cause instanceof ModelException modelException) {
        throw modelException;
      }
      throw new ModelException(
          ModelErrorType.PROVIDER_UNAVAILABLE, "Local model request failed", true, cause);
    } finally {
      executor.shutdownNow();
    }
  }

  private String exchange(ModelRequest request, AtomicReference<SocketChannel> active) {
    try {
      var body = requestBody(request);
      var header =
          "POST /v1/chat/completions HTTP/1.1\r\n"
              + "Host: localhost\r\n"
              + "Content-Type: application/json\r\n"
              + "Accept: application/json\r\n"
              + "Connection: close\r\n"
              + "Content-Length: "
              + body.length
              + "\r\n\r\n";
      try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
        active.set(channel);
        channel.connect(UnixDomainSocketAddress.of(socket));
        write(channel, header.getBytes(StandardCharsets.US_ASCII));
        write(channel, body);
        return parseResponse(read(channel));
      } finally {
        active.set(null);
      }
    } catch (IOException exception) {
      throw new ModelException(
          ModelErrorType.PROVIDER_UNAVAILABLE,
          "Local model socket communication failed",
          true,
          exception);
    }
  }

  private byte[] requestBody(ModelRequest request) throws IOException {
    var system =
        request.systemPrompt() + "\nReturn JSON matching this schema: " + request.outputSchema();
    var payload =
        Map.of(
            "model",
            request.model(),
            "temperature",
            request.temperature(),
            "stream",
            false,
            "messages",
            List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", request.userPrompt())));
    return mapper.writeValueAsBytes(payload);
  }

  private String parseResponse(byte[] response) throws IOException {
    var separator = indexOf(response, new byte[] {'\r', '\n', '\r', '\n'});
    if (separator < 0) {
      throw invalid("Local model returned an invalid HTTP response");
    }
    var header = new String(response, 0, separator, StandardCharsets.US_ASCII);
    var statusLine = header.lines().findFirst().orElse("");
    if (!statusLine.matches("HTTP/1\\.[01] 2[0-9]{2} .*")) {
      throw invalid("Local model returned a non-success HTTP status");
    }
    var body = java.util.Arrays.copyOfRange(response, separator + 4, response.length);
    if (header.toLowerCase(java.util.Locale.ROOT).contains("transfer-encoding: chunked")) {
      body = decodeChunks(body);
    }
    JsonNode json = mapper.readTree(body);
    var content = json.path("choices").path(0).path("message").path("content").asText("");
    if (content.isBlank()) {
      throw invalid("Local model returned empty content");
    }
    return content;
  }

  private static byte[] read(SocketChannel channel) throws IOException {
    var output = new ByteArrayOutputStream();
    var buffer = ByteBuffer.allocate(8192);
    while (channel.read(buffer) >= 0) {
      buffer.flip();
      var chunk = new byte[buffer.remaining()];
      buffer.get(chunk);
      output.write(chunk);
      if (output.size() > MAXIMUM_RESPONSE_BYTES) {
        throw new IOException("model response exceeds configured maximum");
      }
      buffer.clear();
    }
    return output.toByteArray();
  }

  private static void write(SocketChannel channel, byte[] value) throws IOException {
    var buffer = ByteBuffer.wrap(value);
    while (buffer.hasRemaining()) {
      channel.write(buffer);
    }
  }

  private static byte[] decodeChunks(byte[] body) throws IOException {
    var output = new ByteArrayOutputStream();
    var offset = 0;
    while (offset < body.length) {
      var lineEnd = indexOf(body, new byte[] {'\r', '\n'}, offset);
      if (lineEnd < 0) {
        throw new IOException("invalid chunked model response");
      }
      int size;
      try {
        size =
            Integer.parseInt(
                new String(body, offset, lineEnd - offset, StandardCharsets.US_ASCII), 16);
      } catch (NumberFormatException exception) {
        throw new IOException("invalid chunked model response", exception);
      }
      if (size == 0) {
        break;
      }
      offset = lineEnd + 2;
      if (size < 0 || offset + size + 2 > body.length) {
        throw new IOException("invalid chunked model response");
      }
      output.write(body, offset, size);
      offset += size + 2;
    }
    return output.toByteArray();
  }

  private static int indexOf(byte[] value, byte[] target) {
    return indexOf(value, target, 0);
  }

  private static int indexOf(byte[] value, byte[] target, int start) {
    outer:
    for (var index = start; index <= value.length - target.length; index++) {
      for (var offset = 0; offset < target.length; offset++) {
        if (value[index + offset] != target[offset]) {
          continue outer;
        }
      }
      return index;
    }
    return -1;
  }

  private static ModelException invalid(String message) {
    return new ModelException(ModelErrorType.INVALID_RESPONSE, message, false);
  }

  private static int estimate(String text) {
    return Math.max(1, (text.codePointCount(0, text.length()) + 3) / 4);
  }

  private static void close(SocketChannel channel) {
    if (channel != null) {
      try {
        channel.close();
      } catch (IOException ignored) {
        // The timeout result is authoritative; close failure is not exposed.
      }
    }
  }
}

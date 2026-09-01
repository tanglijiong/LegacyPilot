package io.legacypilot.model.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.model.ModelErrorType;
import io.legacypilot.model.ModelException;
import io.legacypilot.model.ModelRequest;
import java.io.ByteArrayOutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnixSocketRawModelClientTest {
  @TempDir Path temporary;

  @Test
  void callsOpenAiCompatibleModelOnlyThroughUnixSocket() throws Exception {
    var socket = temporary.resolve("model.sock");
    try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
      server.bind(UnixDomainSocketAddress.of(socket));
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var received =
            executor.submit(
                () -> {
                  try (var channel = server.accept()) {
                    var request = readRequest(channel);
                    var body =
                        "{\"choices\":[{\"message\":{\"content\":\"{\\\"answer\\\":true}\"}}]}";
                    var response =
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                            + body.getBytes(StandardCharsets.UTF_8).length
                            + "\r\nConnection: close\r\n\r\n"
                            + body;
                    var output = ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8));
                    while (output.hasRemaining()) {
                      channel.write(output);
                    }
                    return request;
                  }
                });
        var mapper = new ObjectMapper();
        var result =
            new UnixSocketRawModelClient(socket, mapper)
                .complete(
                    new ModelRequest(
                        "system",
                        "user",
                        mapper.readTree("{\"type\":\"object\"}"),
                        "deepseek",
                        0,
                        Duration.ofSeconds(5),
                        Map.of()));
        assertEquals("{\"answer\":true}", result.content());
        assertEquals("deepseek", result.usage().model());
        assertTrue(received.get().contains("POST /v1/chat/completions"));
        assertTrue(received.get().contains("\"model\":\"deepseek\""));
      }
    }
  }

  @Test
  void rejectsAPathWithoutASocketFileName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new UnixSocketRawModelClient(Path.of("/"), new ObjectMapper()));
  }

  @Test
  void reportsAnUnavailableSocketWithoutAttemptingAnIpConnection() throws Exception {
    var client =
        new UnixSocketRawModelClient(temporary.resolve("missing.sock"), new ObjectMapper());
    var exception = assertThrows(ModelException.class, () -> client.complete(request()));
    assertEquals(ModelErrorType.PROVIDER_UNAVAILABLE, exception.type());
    assertTrue(exception.retryable());
  }

  @Test
  void acceptsChunkedResponses() throws Exception {
    var body = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
    var chunked = Integer.toHexString(body.length()) + "\r\n" + body + "\r\n0\r\n\r\n";
    var response =
        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n" + chunked;
    assertEquals("ok", completeWithResponse(response, Duration.ofSeconds(2)));
  }

  @Test
  void classifiesInvalidModelResponses() throws Exception {
    var responses =
        List.of(
            "not-http",
            "HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n{}\r\n0\r\n\r\n");
    for (var response : responses) {
      var exception =
          assertThrows(
              ModelException.class, () -> completeWithResponse(response, Duration.ofSeconds(2)));
      assertTrue(
          exception.type() == ModelErrorType.INVALID_RESPONSE
              || exception.type() == ModelErrorType.PROVIDER_UNAVAILABLE);
    }
  }

  @Test
  void closesTheSocketWhenTheModelTimesOut() throws Exception {
    var socket = temporary.resolve("slow.sock");
    try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
      server.bind(UnixDomainSocketAddress.of(socket));
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var accepted =
            executor.submit(
                () -> {
                  try (var channel = server.accept()) {
                    readRequest(channel);
                    channel.read(ByteBuffer.allocate(1));
                    return true;
                  }
                });
        var client = new UnixSocketRawModelClient(socket, new ObjectMapper());
        var exception =
            assertThrows(
                ModelException.class, () -> client.complete(request(Duration.ofMillis(100))));
        assertEquals(ModelErrorType.TIMEOUT, exception.type());
        assertTrue(accepted.get());
      }
    }
  }

  private String completeWithResponse(String response, Duration timeout) throws Exception {
    var socket = temporary.resolve("model-" + System.nanoTime() + ".sock");
    try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
      server.bind(UnixDomainSocketAddress.of(socket));
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var sender =
            executor.submit(
                () -> {
                  try (var channel = server.accept()) {
                    readRequest(channel);
                    var output = ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8));
                    while (output.hasRemaining()) {
                      channel.write(output);
                    }
                    return true;
                  }
                });
        var value =
            new UnixSocketRawModelClient(socket, new ObjectMapper())
                .complete(request(timeout))
                .content();
        assertTrue(sender.get());
        return value;
      }
    }
  }

  private static ModelRequest request() throws Exception {
    return request(Duration.ofSeconds(2));
  }

  private static ModelRequest request(Duration timeout) throws Exception {
    var mapper = new ObjectMapper();
    return new ModelRequest(
        "system",
        "user",
        mapper.readTree("{\"type\":\"object\"}"),
        "deepseek",
        0,
        timeout,
        Map.of());
  }

  private static String readRequest(java.nio.channels.SocketChannel channel) throws Exception {
    var output = new ByteArrayOutputStream();
    var buffer = ByteBuffer.allocate(4096);
    var contentLength = -1;
    var bodyStart = -1;
    while (true) {
      var count = channel.read(buffer);
      if (count < 0) {
        break;
      }
      buffer.flip();
      var chunk = new byte[buffer.remaining()];
      buffer.get(chunk);
      output.write(chunk);
      buffer.clear();
      var value = output.toString(StandardCharsets.UTF_8);
      if (bodyStart < 0 && value.contains("\r\n\r\n")) {
        bodyStart = value.indexOf("\r\n\r\n") + 4;
        for (var line : value.substring(0, bodyStart).split("\r\n")) {
          if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
            contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
          }
        }
      }
      if (bodyStart >= 0 && contentLength >= 0 && output.size() >= bodyStart + contentLength) {
        break;
      }
    }
    return output.toString(StandardCharsets.UTF_8);
  }
}

package io.legacypilot.model.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.legacypilot.model.ModelErrorType;
import io.legacypilot.model.ModelException;
import io.legacypilot.model.ModelRequest;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalHttpRawModelClientTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void callsDockerModelRunnerCompatibleLoopbackEndpoint() throws Exception {
    var server = server(200, response("{\"answer\":true}", 11, 7), Duration.ZERO);
    try {
      var result = client(server).complete(request(Duration.ofSeconds(5)));
      assertEquals("{\"answer\":true}", result.content());
      assertEquals(11, result.usage().inputTokens());
      assertEquals(7, result.usage().outputTokens());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsNonLoopbackAndCredentialBearingUrls() {
    for (var value :
        new String[] {
          "https://localhost:12434/engines/v1",
          "http://example.com:12434/engines/v1",
          "http://token@localhost:12434/engines/v1",
          "http://localhost/engines/v1"
        }) {
      assertThrows(
          IllegalArgumentException.class, () -> new LocalHttpRawModelClient(value, mapper));
    }
  }

  @Test
  void classifiesServerErrorsAndTimeouts() throws Exception {
    var unavailable = server(503, "{}", Duration.ZERO);
    try {
      var exception =
          assertThrows(
              ModelException.class,
              () -> client(unavailable).complete(request(Duration.ofSeconds(2))));
      assertEquals(ModelErrorType.PROVIDER_UNAVAILABLE, exception.type());
      assertTrue(exception.retryable());
    } finally {
      unavailable.stop(0);
    }
    var slow = server(200, response("ok", 1, 1), Duration.ofMillis(300));
    try {
      var exception =
          assertThrows(
              ModelException.class, () -> client(slow).complete(request(Duration.ofMillis(50))));
      assertEquals(ModelErrorType.TIMEOUT, exception.type());
    } finally {
      slow.stop(0);
    }
  }

  private HttpServer server(int status, String response, Duration delay) throws Exception {
    var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/engines/v1/chat/completions",
        exchange -> {
          try {
            exchange.getRequestBody().readAllBytes();
            if (!delay.isZero()) {
              Thread.sleep(delay);
            }
            var body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    return server;
  }

  private LocalHttpRawModelClient client(HttpServer server) {
    return new LocalHttpRawModelClient(
        "http://127.0.0.1:" + server.getAddress().getPort() + "/engines/v1", mapper);
  }

  private ModelRequest request(Duration timeout) throws Exception {
    return new ModelRequest(
        "system",
        "user",
        mapper.readTree("{\"type\":\"object\"}"),
        "hf.co/bartowski/deepseek-coder-v2-lite-instruct-gguf:q4_k_m",
        0,
        timeout,
        Map.of());
  }

  private static String response(String content, int input, int output) {
    return "{\"choices\":[{\"message\":{\"content\":"
        + quote(content)
        + "}}],\"usage\":{\"prompt_tokens\":"
        + input
        + ",\"completion_tokens\":"
        + output
        + "}}";
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}

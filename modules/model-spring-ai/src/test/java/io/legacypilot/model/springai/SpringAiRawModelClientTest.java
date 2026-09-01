package io.legacypilot.model.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.model.ModelCostTable;
import io.legacypilot.model.ModelErrorType;
import io.legacypilot.model.ModelException;
import io.legacypilot.model.ModelRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class SpringAiRawModelClientTest {

  @Test
  void returnsAProviderResponseWithLocalCostAccounting() throws Exception {
    ChatModel model =
        prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("result"))));
    var response =
        new SpringAiRawModelClient(model, new ModelCostTable(Map.of())).complete(request());
    assertEquals("result", response.content());
    assertEquals("model", response.usage().model());
  }

  @Test
  void classifiesNullAndProviderFailures() throws Exception {
    ChatModel empty = prompt -> null;
    var invalid =
        assertThrows(
            ModelException.class,
            () ->
                new SpringAiRawModelClient(empty, new ModelCostTable(Map.of()))
                    .complete(request()));
    assertEquals(ModelErrorType.INVALID_RESPONSE, invalid.type());

    ChatModel unauthorized =
        prompt -> {
          throw new IllegalStateException("401 unauthorized");
        };
    var authentication =
        assertThrows(
            ModelException.class,
            () ->
                new SpringAiRawModelClient(unauthorized, new ModelCostTable(Map.of()))
                    .complete(request()));
    assertEquals(ModelErrorType.AUTHENTICATION, authentication.type());
  }

  private static ModelRequest request() throws Exception {
    return new ModelRequest(
        "system",
        "user",
        new ObjectMapper().readTree("{\"type\":\"object\"}"),
        "model",
        0,
        Duration.ofSeconds(2),
        Map.of());
  }
}

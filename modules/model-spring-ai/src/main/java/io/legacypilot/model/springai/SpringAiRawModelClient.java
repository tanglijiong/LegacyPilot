package io.legacypilot.model.springai;

import io.legacypilot.model.ModelCostTable;
import io.legacypilot.model.ModelErrorType;
import io.legacypilot.model.ModelException;
import io.legacypilot.model.ModelRequest;
import io.legacypilot.model.RawModelClient;
import io.legacypilot.model.RawModelResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

public final class SpringAiRawModelClient implements RawModelClient {

  private final ChatModel chatModel;
  private final ModelCostTable costs;

  public SpringAiRawModelClient(ChatModel chatModel, ModelCostTable costs) {
    this.chatModel = Objects.requireNonNull(chatModel);
    this.costs = Objects.requireNonNull(costs);
  }

  @Override
  public RawModelResponse complete(ModelRequest request) {
    var started = Instant.now();
    var options =
        ChatOptions.builder().model(request.model()).temperature(request.temperature()).build();
    var prompt =
        new Prompt(
            List.of(
                new SystemMessage(
                    request.systemPrompt()
                        + "\nReturn JSON matching this schema: "
                        + request.outputSchema()),
                new UserMessage(request.userPrompt())),
            options);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var future = executor.submit(() -> chatModel.call(prompt));
      var response = future.get(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
      if (response == null) {
        throw new ModelException(
            ModelErrorType.INVALID_RESPONSE, "Model provider returned no result", false);
      }
      var content = response.getResult().getOutput().getText();
      if (content == null || content.isBlank()) {
        throw new ModelException(
            ModelErrorType.INVALID_RESPONSE, "Model provider returned empty content", false);
      }
      var input = estimate(request.systemPrompt() + request.userPrompt());
      var output = estimate(content);
      return new RawModelResponse(
          content,
          costs.price(request.model(), input, output),
          Duration.between(started, Instant.now()));
    } catch (TimeoutException exception) {
      throw new ModelException(ModelErrorType.TIMEOUT, "Model request timed out", true, exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ModelException(
          ModelErrorType.INTERNAL, "Model request interrupted", true, exception);
    } catch (ExecutionException exception) {
      throw classify(exception.getCause());
    }
  }

  private static ModelException classify(Throwable cause) {
    var name = cause.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
    var message = Objects.toString(cause.getMessage(), "").toLowerCase(java.util.Locale.ROOT);
    if (name.contains("auth") || message.contains("unauthorized") || message.contains("401")) {
      return new ModelException(
          ModelErrorType.AUTHENTICATION, "Model authentication failed", false, cause);
    }
    if (name.contains("ratelimit") || message.contains("429")) {
      return new ModelException(
          ModelErrorType.RATE_LIMIT, "Model rate limit exceeded", true, cause);
    }
    return new ModelException(
        ModelErrorType.PROVIDER_UNAVAILABLE, "Model provider request failed", true, cause);
  }

  private static int estimate(String text) {
    return Math.max(1, (text.codePointCount(0, text.length()) + 3) / 4);
  }
}

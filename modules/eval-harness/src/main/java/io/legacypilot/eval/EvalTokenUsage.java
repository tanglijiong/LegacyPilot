package io.legacypilot.eval;

public record EvalTokenUsage(
    int inputTokens, int cachedInputTokens, int outputTokens, int reasoningOutputTokens) {
  public static final EvalTokenUsage NONE = new EvalTokenUsage(0, 0, 0, 0);

  public EvalTokenUsage {
    if (inputTokens < 0
        || cachedInputTokens < 0
        || outputTokens < 0
        || reasoningOutputTokens < 0
        || cachedInputTokens > inputTokens
        || reasoningOutputTokens > outputTokens) {
      throw new IllegalArgumentException("eval token usage is invalid");
    }
  }

  public int totalTokens() {
    return inputTokens + outputTokens;
  }
}

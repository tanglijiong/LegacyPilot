package io.legacypilot.eval;

import java.math.BigDecimal;
import java.util.Objects;

public record EvalPricingSnapshot(
    String currency,
    String unit,
    BigDecimal input,
    BigDecimal cachedInput,
    BigDecimal output,
    String source) {
  public EvalPricingSnapshot {
    Objects.requireNonNull(currency);
    Objects.requireNonNull(unit);
    Objects.requireNonNull(input);
    Objects.requireNonNull(cachedInput);
    Objects.requireNonNull(output);
    Objects.requireNonNull(source);
    if (currency.isBlank()
        || unit.isBlank()
        || source.isBlank()
        || input.signum() < 0
        || cachedInput.signum() < 0
        || output.signum() < 0) {
      throw new IllegalArgumentException("eval pricing snapshot is invalid");
    }
  }

  public BigDecimal price(EvalTokenUsage usage) {
    Objects.requireNonNull(usage);
    var uncached = usage.inputTokens() - usage.cachedInputTokens();
    return input
        .multiply(BigDecimal.valueOf(uncached))
        .add(cachedInput.multiply(BigDecimal.valueOf(usage.cachedInputTokens())))
        .add(output.multiply(BigDecimal.valueOf(usage.outputTokens())))
        .divide(BigDecimal.valueOf(1_000_000));
  }
}

package io.legacypilot.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;

public final class ModelCostTable {

  private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
  private final Map<String, Price> prices;

  public ModelCostTable(Map<String, Price> prices) {
    this.prices = Map.copyOf(prices);
  }

  public ModelUsage price(String model, int inputTokens, int outputTokens) {
    var price = prices.get(model);
    if (price == null) {
      return new ModelUsage(inputTokens, outputTokens, BigDecimal.ZERO, model);
    }
    var cost =
        price
            .inputPerMillion
            .multiply(BigDecimal.valueOf(inputTokens))
            .add(price.outputPerMillion.multiply(BigDecimal.valueOf(outputTokens)))
            .divide(MILLION, 8, RoundingMode.HALF_UP);
    return new ModelUsage(inputTokens, outputTokens, cost, model);
  }

  public record Price(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
    public Price {
      Objects.requireNonNull(inputPerMillion);
      Objects.requireNonNull(outputPerMillion);
      if (inputPerMillion.signum() < 0 || outputPerMillion.signum() < 0) {
        throw new IllegalArgumentException("model prices must not be negative");
      }
    }
  }
}

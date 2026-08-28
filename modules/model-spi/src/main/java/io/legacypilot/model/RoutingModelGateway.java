package io.legacypilot.model;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RoutingModelGateway implements ModelGateway {
  private final List<ModelProfile> profiles;
  private final Map<String, ModelGateway> providers;
  private final ModelRoutingBudget budget;
  private final ProviderCircuitBreaker circuits;
  private final ModelRoutingListener listener;
  private final Clock clock;

  public RoutingModelGateway(
      List<ModelProfile> profiles,
      Map<String, ModelGateway> providers,
      ModelRoutingBudget budget,
      ProviderCircuitBreaker circuits,
      ModelRoutingListener listener,
      Clock clock) {
    this.profiles =
        profiles.stream()
            .sorted(
                Comparator.comparingInt(ModelProfile::priority)
                    .reversed()
                    .thenComparing(ModelProfile::id))
            .toList();
    this.providers = Map.copyOf(providers);
    this.budget = Objects.requireNonNull(budget);
    this.circuits = Objects.requireNonNull(circuits);
    this.listener = Objects.requireNonNull(listener);
    this.clock = Objects.requireNonNull(clock);
    if (this.profiles.isEmpty()
        || this.profiles.stream()
            .anyMatch(profile -> !this.providers.containsKey(profile.provider()))) {
      throw new IllegalArgumentException("every model profile requires a registered provider");
    }
  }

  @Override
  public <T> ModelResult<T> generate(ModelRequest request, Class<T> responseType) {
    var phase = request.metadata().getOrDefault("phase", "default");
    var candidates = profiles.stream().filter(profile -> profile.supports(phase)).toList();
    if (candidates.isEmpty()) {
      throw new ModelException(
          ModelErrorType.PROVIDER_UNAVAILABLE,
          "No model route supports the requested phase",
          false);
    }
    ModelException last = null;
    var attempts = 0;
    var tokens = 0;
    var cost = BigDecimal.ZERO;
    for (var profile : candidates) {
      if (attempts >= budget.maximumAttempts()) {
        break;
      }
      if (!circuits.allow(profile.provider())) {
        listener.record(
            event(
                profile,
                attempts + 1,
                ModelRouteEvent.Outcome.CIRCUIT_OPEN,
                null,
                tokens,
                cost,
                Duration.ZERO));
        continue;
      }
      attempts++;
      var started = clock.instant();
      try {
        var result =
            providers
                .get(profile.provider())
                .generate(request.withModel(profile.model()), responseType);
        tokens += result.usage().totalTokens();
        cost = cost.add(result.usage().estimatedCostUsd());
        if (tokens > budget.maximumTokens() || cost.compareTo(budget.maximumCostUsd()) > 0) {
          listener.record(
              event(
                  profile,
                  attempts,
                  ModelRouteEvent.Outcome.BUDGET_EXHAUSTED,
                  null,
                  tokens,
                  cost,
                  Duration.between(started, clock.instant())));
          throw new ModelException(
              ModelErrorType.INTERNAL, "Model routing budget exhausted", false);
        }
        circuits.success(profile.provider());
        listener.record(
            event(
                profile,
                attempts,
                ModelRouteEvent.Outcome.SUCCEEDED,
                null,
                tokens,
                cost,
                result.duration()));
        return result;
      } catch (ModelException exception) {
        last = exception;
        var fallback = fallbackAllowed(exception);
        if (fallback) {
          circuits.failure(profile.provider());
        }
        listener.record(
            event(
                profile,
                attempts,
                ModelRouteEvent.Outcome.FAILED,
                exception.type(),
                tokens,
                cost,
                Duration.between(started, clock.instant())));
        if (!fallback) {
          throw exception;
        }
      }
    }
    if (last != null) {
      throw new ModelException(last.type(), "All eligible model routes failed", last.retryable());
    }
    throw new ModelException(
        ModelErrorType.PROVIDER_UNAVAILABLE, "All eligible model circuits are open", true);
  }

  private ModelRouteEvent event(
      ModelProfile profile,
      int attempt,
      ModelRouteEvent.Outcome outcome,
      ModelErrorType error,
      int tokens,
      BigDecimal cost,
      Duration duration) {
    return new ModelRouteEvent(
        profile.id(),
        profile.provider(),
        profile.model(),
        attempt,
        outcome,
        error,
        tokens,
        cost,
        duration,
        clock.instant());
  }

  private static boolean fallbackAllowed(ModelException exception) {
    return exception.retryable()
        && (exception.type() == ModelErrorType.RATE_LIMIT
            || exception.type() == ModelErrorType.TIMEOUT
            || exception.type() == ModelErrorType.PROVIDER_UNAVAILABLE);
  }
}

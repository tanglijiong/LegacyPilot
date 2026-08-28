package io.legacypilot.model;

@FunctionalInterface
public interface ModelRoutingListener {
  void record(ModelRouteEvent event);

  static ModelRoutingListener noop() {
    return ignored -> {};
  }
}

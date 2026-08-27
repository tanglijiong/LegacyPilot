package io.legacypilot.context;

@FunctionalInterface
public interface TokenEstimator {
  int estimate(String text);

  static TokenEstimator conservative() {
    return text -> Math.max(1, (text.codePointCount(0, text.length()) + 2) / 3);
  }
}

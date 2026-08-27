package io.legacypilot.application.port;

public interface IdGenerator {
  String next(String prefix);
}

package io.legacypilot.tool.spi;

public enum RiskLevel {
  READ_ONLY,
  WORKSPACE_WRITE,
  COMMAND_EXECUTION,
  EXTERNAL_IO
}

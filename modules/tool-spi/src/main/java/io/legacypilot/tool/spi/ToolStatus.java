package io.legacypilot.tool.spi;

public enum ToolStatus {
  SUCCESS,
  SCHEMA_ERROR,
  POLICY_DENIED,
  APPROVAL_REQUIRED,
  TIMEOUT,
  TOOL_ERROR,
  SYSTEM_ERROR
}

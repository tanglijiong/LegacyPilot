package io.legacypilot.analysis.java;

public enum DependencyKind {
  EXTENDS,
  IMPLEMENTS,
  IMPORTS,
  FIELD_TYPE,
  INJECTS,
  METHOD_CALL,
  REFERENCES
}

package io.legacypilot.runtime;

import io.legacypilot.observability.TaskArtifact;
import java.util.stream.Stream;

final class ResponseLanguage {

  private static final String CURRENT_QUESTION_MARKER = "当前用户问题：";

  private ResponseLanguage() {}

  static String instruction(String requirement) {
    if (isChineseQuestion(requirement)) {
      return "The current user question is Chinese. Write every user-facing natural-language "
          + "field in Simplified Chinese, including plan steps, rationale, reasons, summaries, "
          + "section claims, risks, and unknowns. Keep code, paths, symbols, tool names, and "
          + "machine-readable enum values unchanged.";
    }
    return "Write every user-facing natural-language field in the same language as the current "
        + "user question. Keep code, paths, symbols, tool names, and machine-readable enum values "
        + "unchanged.";
  }

  static boolean matches(AgentRunRequest request, TaskArtifact artifact) {
    if (!isChineseQuestion(request.requirement())) {
      return true;
    }
    return Stream.concat(
            Stream.concat(
                Stream.of(artifact.summary()),
                artifact.sections().values().stream().flatMap(java.util.Collection::stream)),
            artifact.unknowns().stream())
        .filter(value -> !value.isBlank())
        .allMatch(ResponseLanguage::containsChinese);
  }

  private static boolean isChineseQuestion(String requirement) {
    var currentQuestion = requirement;
    var marker = requirement.lastIndexOf(CURRENT_QUESTION_MARKER);
    if (marker >= 0) {
      currentQuestion = requirement.substring(marker + CURRENT_QUESTION_MARKER.length());
    }
    return containsChinese(currentQuestion);
  }

  private static boolean containsChinese(String value) {
    return value.codePoints().anyMatch(codePoint -> codePoint >= 0x3400 && codePoint <= 0x9fff);
  }
}

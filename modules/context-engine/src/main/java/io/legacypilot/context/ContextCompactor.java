package io.legacypilot.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class ContextCompactor {
  private final TokenEstimator tokens;

  public ContextCompactor(TokenEstimator tokens) {
    this.tokens = Objects.requireNonNull(tokens);
  }

  public CompactedContext compact(List<TaskMemory> memories, int budget, int version) {
    if (budget < 1 || version < 1) {
      throw new IllegalArgumentException("compaction budget and version must be positive");
    }
    var ordered =
        memories.stream()
            .sorted(
                Comparator.comparingInt(ContextCompactor::priority)
                    .thenComparing(TaskMemory::createdAt)
                    .reversed())
            .toList();
    var retained = new ArrayList<String>();
    var lines = new ArrayList<String>();
    var decisions = new ArrayList<ContextDecision>();
    var used = 0;
    for (var memory : ordered) {
      var line = render(memory);
      var estimate = tokens.estimate(line);
      if (estimate > budget - used) {
        var summary = summarize(memory);
        estimate = tokens.estimate(summary);
        if (estimate > budget - used) {
          decisions.add(new ContextDecision(memory.id(), "compaction budget exhausted"));
          continue;
        }
        line = summary;
        decisions.add(new ContextDecision(memory.id(), "deterministic summary retained"));
      }
      lines.add(line);
      retained.add(memory.id());
      used += estimate;
    }
    return new CompactedContext(version, String.join("\n", lines), used, retained, decisions);
  }

  private static int priority(TaskMemory memory) {
    return switch (memory.kind()) {
      case PENDING_ACTION -> 5;
      case DECISION -> 4;
      case FAILURE -> 3;
      case SOURCE_EVIDENCE -> 2;
      case FACT -> 1;
    };
  }

  private static String render(TaskMemory memory) {
    return "[memory:"
        + memory.id()
        + "]["
        + memory.kind()
        + "]["
        + (memory.verified() ? "verified" : "unverified")
        + "][sources="
        + sources(memory)
        + "] "
        + memory.content();
  }

  private static String summarize(TaskMemory memory) {
    var content = memory.content();
    var shortened = content.length() <= 160 ? content : content.substring(0, 160) + "…";
    return "[memory:"
        + memory.id()
        + "]["
        + memory.kind()
        + "][summary][sources="
        + sources(memory)
        + "] "
        + shortened;
  }

  private static String sources(TaskMemory memory) {
    return memory.sources().stream().sorted().collect(java.util.stream.Collectors.joining(","));
  }
}

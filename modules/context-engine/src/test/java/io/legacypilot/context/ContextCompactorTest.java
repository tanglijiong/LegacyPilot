package io.legacypilot.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ContextCompactorTest {
  @Test
  void keepsTwoHundredStepHistoryInsideBudgetAndPreservesPendingWork() {
    var now = Instant.parse("2026-08-27T00:00:00Z");
    var memories =
        IntStream.range(0, 200)
            .mapToObj(
                index ->
                    new TaskMemory(
                        "memory-" + index,
                        "task-1",
                        index == 199 ? MemoryKind.PENDING_ACTION : MemoryKind.FACT,
                        "observation-" + index + " " + "detail ".repeat(30),
                        Set.of("trace:" + index),
                        true,
                        now.plusSeconds(index),
                        now.plusSeconds(10_000)))
            .toList();

    var compacted = new ContextCompactor(TokenEstimator.conservative()).compact(memories, 500, 7);

    assertTrue(compacted.estimatedTokens() <= 500);
    assertTrue(compacted.retainedMemoryIds().contains("memory-199"));
    assertTrue(compacted.content().contains("sources=trace:199"));
    assertFalse(compacted.decisions().isEmpty());
    assertEquals(7, compacted.version());
  }

  @Test
  void memoryExpiresAndCanBeDeleted() {
    var store = new InMemoryTaskMemoryStore(10);
    var now = Instant.EPOCH;
    store.append(
        new TaskMemory(
            "active",
            "task",
            MemoryKind.DECISION,
            "keep",
            Set.of(),
            true,
            now,
            now.plusSeconds(2)));
    store.append(
        new TaskMemory(
            "expired", "task", MemoryKind.FACT, "drop", Set.of(), true, now, now.plusSeconds(1)));

    assertEquals(1, store.active("task", now.plusSeconds(1)).size());
    store.delete("task");
    assertTrue(store.active("task", now).isEmpty());
  }
}

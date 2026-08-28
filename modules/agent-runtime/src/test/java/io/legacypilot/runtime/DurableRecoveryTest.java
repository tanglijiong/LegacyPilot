package io.legacypilot.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DurableRecoveryTest {
  @TempDir java.nio.file.Path temporary;
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void journalsBoundedResultsAcrossRestart() {
    var path = temporary.resolve("actions");
    var journal = new FileActionJournal(path, mapper);
    var record =
        new ActionRecord(
                "000001-deadbeef",
                "run-1",
                "apply_patch",
                "digest",
                "plan",
                ActionStatus.RUNNING,
                1,
                "",
                Instant.EPOCH)
            .transition(
                ActionStatus.SUCCEEDED,
                1,
                "api_key=raw-secret " + "x".repeat(10_000),
                Instant.EPOCH.plusSeconds(1));
    journal.save(record);

    var restored = new FileActionJournal(path, mapper).records("run-1").getFirst();
    assertEquals(ActionStatus.SUCCEEDED, restored.status());
    assertTrue(restored.resultSummary().length() <= 2_048);
    assertFalse(restored.resultSummary().contains("raw-secret"));
    assertTrue(java.nio.file.Files.exists(path.resolve("run-1.json")));
  }

  @Test
  void leaseRejectsConcurrentOwnerAndFencesExpiredOwner() {
    var path = temporary.resolve("leases");
    var firstStore = new FileRunLeaseStore(path, mapper);
    var secondStore = new FileRunLeaseStore(path, mapper);
    var now = Instant.parse("2026-08-27T00:00:00Z");
    var first = firstStore.acquire("run-1", "owner-a", now, Duration.ofSeconds(5)).orElseThrow();

    assertTrue(secondStore.acquire("run-1", "owner-b", now, Duration.ofSeconds(5)).isEmpty());
    assertTrue(firstStore.renew(first, now.plusSeconds(6), Duration.ofSeconds(5)).isEmpty());
    var second =
        secondStore
            .acquire("run-1", "owner-b", now.plusSeconds(6), Duration.ofSeconds(5))
            .orElseThrow();
    assertEquals(first.epoch() + 1, second.epoch());
    assertTrue(firstStore.renew(first, now.plusSeconds(7), Duration.ofSeconds(5)).isEmpty());
    assertFalse(secondStore.renew(second, now.plusSeconds(7), Duration.ofSeconds(5)).isEmpty());
  }

  @Test
  void fileMemoryHonorsTtlCapacityAndDeletion() {
    var store = new FileTaskMemoryStore(temporary.resolve("memory"), mapper, 2);
    var now = Instant.EPOCH;
    for (var index = 0; index < 3; index++) {
      store.append(
          new io.legacypilot.context.TaskMemory(
              "id-" + index,
              "task-1",
              io.legacypilot.context.MemoryKind.FACT,
              "value-" + index,
              java.util.Set.of("source"),
              true,
              now.plusSeconds(index),
              now.plusSeconds(10)));
    }
    assertEquals(2, store.active("task-1", now.plusSeconds(3)).size());
    store.delete("task-1");
    assertTrue(store.active("task-1", now.plusSeconds(3)).isEmpty());
  }
}

package io.legacypilot.fixtures.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class Task011HiddenTest {
  @Test
  void appliesExponentialBackoffWithOneHourCap() {
    var policy = new RetryPolicy();

    assertEquals(30, policy.nextAttempt(Instant.EPOCH, 0).getEpochSecond());
    assertEquals(240, policy.nextAttempt(Instant.EPOCH, 3).getEpochSecond());
    assertEquals(3600, policy.nextAttempt(Instant.EPOCH, 20).getEpochSecond());
    assertThrows(IllegalArgumentException.class, () -> policy.nextAttempt(Instant.EPOCH, -1));
  }
}

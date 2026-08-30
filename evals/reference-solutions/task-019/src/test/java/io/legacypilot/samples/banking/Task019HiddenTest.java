package io.legacypilot.samples.banking;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Task019HiddenTest {
  @Test
  void migrationIndexesTheHistoryLookupColumnsInOrder() throws Exception {
    try (var input =
        getClass().getResourceAsStream("/db/migration/V2__transfer_history_index.sql")) {
      assertTrue(input != null);
      var sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
      assertTrue(sql.contains("create index"));
      assertTrue(sql.contains("on transfer (account_id, occurred_at)"));
    }
  }
}

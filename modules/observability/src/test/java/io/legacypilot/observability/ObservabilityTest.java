package io.legacypilot.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObservabilityTest {

  @TempDir Path directory;

  @Test
  void redactsTracesAndRecordsMetrics() {
    var redactor = new SensitiveDataRedactor(64);
    assertEquals("[REDACTED]", redactor.redact("Authorization", "Bearer raw-secret"));
    assertEquals(
        "connect [REDACTED]", redactor.redact("message", "connect jdbc:postgresql://secret"));
    assertTrue(redactor.redact("message", "x".repeat(100)).endsWith("[TRUNCATED]"));
    assertThrows(IllegalArgumentException.class, () -> new SensitiveDataRedactor(10));

    var trace = new InMemoryTraceSink(redactor);
    trace.append(
        new TraceEvent(
            "run-1", 2, "tool", Instant.parse("2026-08-27T00:00:02Z"), Map.of("token", "secret")));
    trace.append(
        new TraceEvent("run-1", 1, "start", Instant.parse("2026-08-27T00:00:01Z"), Map.of()));
    assertEquals(1, trace.events("run-1").getFirst().sequence());
    assertEquals("[REDACTED]", trace.events("run-1").get(1).attributes().get("token"));

    var registry = new SimpleMeterRegistry();
    var metrics = new AgentMetrics(registry);
    metrics.modelUsage(15);
    metrics.toolInvocation("read_file", "SUCCESS");
    metrics.runCompleted("SUCCEEDED", Duration.ofSeconds(2));
    assertEquals(15, registry.counter("legacypilot.model.tokens").count());
    assertEquals(
        1,
        registry
            .counter("legacypilot.tool.invocations", "tool", "read_file", "status", "SUCCESS")
            .count());
  }

  @Test
  void rendersAndPersistsJsonAndMarkdownReports() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var event = new TraceEvent("run-1", 1, "start", Instant.EPOCH, Map.of());
    var mutableEvidence = new java.util.HashMap<>(Map.of("name", "tests", "status", "PASSED"));
    var report =
        new RunReport(
            "run-1",
            "SUCCEEDED",
            "verified",
            2,
            15,
            new BigDecimal("0.01"),
            Duration.ofSeconds(2),
            "LOW",
            List.of("change", "verify"),
            List.of(mutableEvidence),
            List.of(event));
    mutableEvidence.put("status", "FAILED");

    var renderer = new ReportRenderer(mapper);
    assertTrue(renderer.json(report).contains("SUCCEEDED"));
    assertTrue(renderer.markdown(report).contains("tests: PASSED"));
    assertEquals("PASSED", report.verification().getFirst().get("status"));

    var store = new FileReportStore(directory, mapper);
    store.save(report);
    assertEquals("SUCCEEDED", store.load("run-1").orElseThrow().status());
    assertTrue(Files.readString(directory.resolve("run-1.md")).contains("# LegacyPilot Run"));
    assertTrue(store.load("missing").isEmpty());
    assertThrows(IllegalArgumentException.class, () -> store.load("../escape"));
  }

  @Test
  void persistsConcurrentTraceSequenceAcrossRestart() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var trace =
        new FileTraceSink(directory.resolve("traces"), mapper, new SensitiveDataRedactor(256));
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures =
          java.util.stream.IntStream.range(0, 100)
              .mapToObj(
                  index ->
                      executor.submit(
                          () ->
                              trace.record(
                                  "long-run",
                                  "fault.injected",
                                  Instant.EPOCH.plusMillis(index),
                                  Map.of(
                                      "token",
                                      "secret-" + index,
                                      "point",
                                      Integer.toString(index)))))
              .toList();
      for (var future : futures) {
        future.get();
      }
    }

    var restored =
        new FileTraceSink(directory.resolve("traces"), mapper, new SensitiveDataRedactor(256));
    var events = restored.events("long-run");
    assertEquals(100, events.size());
    assertEquals(
        java.util.stream.IntStream.rangeClosed(1, 100).boxed().toList(),
        events.stream().map(TraceEvent::sequence).toList());
    assertTrue(
        events.stream().allMatch(event -> event.attributes().get("token").equals("[REDACTED]")));
  }

  @Test
  void validatesAppendSequenceAndQuarantinesInterruptedTail() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var root = directory.resolve("trace-validation");
    var trace = new FileTraceSink(root, mapper, new SensitiveDataRedactor(256));
    trace.append(new TraceEvent("run-2", 1, "start", Instant.EPOCH, Map.of()));

    assertThrows(
        IllegalStateException.class,
        () -> trace.append(new TraceEvent("run-2", 3, "gap", Instant.EPOCH, Map.of())));
    assertThrows(IllegalArgumentException.class, () -> trace.events("../escape"));

    var interrupted = root.resolve("interrupted.jsonl");
    Files.writeString(
        interrupted,
        mapper.writeValueAsString(
                new TraceEvent("interrupted", 1, "start", Instant.EPOCH, Map.of()))
            + "\n\n{\"runId\":");
    assertEquals(1, trace.events("interrupted").size());
    assertTrue(Files.exists(root.resolve("interrupted.jsonl.corrupt-tail")));
  }

  @Test
  void defaultTraceRecordAllocatesTheNextSequence() {
    var values = new java.util.ArrayList<TraceEvent>();
    TraceSink trace =
        new TraceSink() {
          @Override
          public void append(TraceEvent event) {
            values.add(event);
          }

          @Override
          public List<TraceEvent> events(String runId) {
            return values.stream().filter(value -> value.runId().equals(runId)).toList();
          }
        };

    trace.append(new TraceEvent("run-3", 1, "start", Instant.EPOCH, Map.of()));
    assertEquals(
        2, trace.record("run-3", "next", Instant.EPOCH.plusSeconds(1), Map.of()).sequence());
  }
}

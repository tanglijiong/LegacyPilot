package io.legacypilot.server.demo;

import io.legacypilot.server.service.RunEventStreamService;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("demo")
@RequestMapping("/api/v1/demo/faults")
public final class DemoFaultController {
  private final RunEventStreamService streams;

  public DemoFaultController(RunEventStreamService streams) {
    this.streams = streams;
  }

  @PostMapping("/disconnect-event-streams")
  Map<String, Integer> disconnectEventStreams() {
    return Map.of("disconnected", streams.disconnectActiveStreams());
  }
}

package io.legacypilot.server.api;

import io.legacypilot.runtime.CapabilityRequest;
import io.legacypilot.runtime.CapabilityService;
import io.legacypilot.runtime.CapabilityView;
import io.legacypilot.runtime.IssuedCapability;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/capabilities")
public final class CapabilityController {
  private final CapabilityService capabilities;

  public CapabilityController(CapabilityService capabilities) {
    this.capabilities = capabilities;
  }

  @PostMapping
  IssuedCapability issue(@RequestBody CapabilityRequest request) {
    return capabilities.issue(request);
  }

  @GetMapping
  List<CapabilityView> list() {
    return capabilities.list();
  }

  @GetMapping("/{id}")
  CapabilityView find(@PathVariable String id) {
    return capabilities
        .find(id)
        .orElseThrow(() -> new IllegalArgumentException("capability was not found"));
  }

  @DeleteMapping("/{id}")
  CapabilityView revoke(@PathVariable String id) {
    return capabilities
        .revoke(id)
        .orElseThrow(() -> new IllegalArgumentException("capability was not found"));
  }
}

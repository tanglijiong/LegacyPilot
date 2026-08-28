package io.legacypilot.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CapabilityGrantStore {
  void save(CapabilityGrant grant);

  Optional<CapabilityGrant> consume(String tokenDigest, CapabilityUse use, Instant now);

  Optional<CapabilityGrant> revoke(String id, Instant now);

  Optional<CapabilityGrant> find(String id);

  List<CapabilityGrant> list();

  int purgeExpired(Instant now);
}

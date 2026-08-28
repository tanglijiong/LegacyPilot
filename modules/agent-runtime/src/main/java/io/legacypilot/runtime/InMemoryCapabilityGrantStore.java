package io.legacypilot.runtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryCapabilityGrantStore implements CapabilityGrantStore {
  private final Map<String, CapabilityGrant> grants = new LinkedHashMap<>();

  @Override
  public synchronized void save(CapabilityGrant grant) {
    grants.put(grant.id(), grant);
  }

  @Override
  public synchronized Optional<CapabilityGrant> consume(
      String tokenDigest, CapabilityUse use, Instant now) {
    var match =
        grants.values().stream()
            .filter(grant -> grant.tokenDigest().equals(tokenDigest) && grant.matches(use, now))
            .findFirst();
    match.map(grant -> grant.consumed(now)).ifPresent(grant -> grants.put(grant.id(), grant));
    return match.map(grant -> grant.consumed(now));
  }

  @Override
  public synchronized Optional<CapabilityGrant> revoke(String id, Instant now) {
    var grant = grants.get(id);
    if (grant == null) {
      return Optional.empty();
    }
    var revoked = grant.revoke(now);
    grants.put(id, revoked);
    return Optional.of(revoked);
  }

  @Override
  public synchronized Optional<CapabilityGrant> find(String id) {
    return Optional.ofNullable(grants.get(id));
  }

  @Override
  public synchronized List<CapabilityGrant> list() {
    return List.copyOf(grants.values());
  }

  @Override
  public synchronized int purgeExpired(Instant now) {
    var expired =
        grants.values().stream()
            .filter(grant -> !now.isBefore(grant.expiresAt()) || grant.revoked())
            .map(CapabilityGrant::id)
            .toList();
    expired.forEach(grants::remove);
    return expired.size();
  }
}

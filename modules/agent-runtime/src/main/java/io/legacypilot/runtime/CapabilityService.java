package io.legacypilot.runtime;

import io.legacypilot.observability.TraceSink;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class CapabilityService {
  private final CapabilityGrantStore store;
  private final TraceSink trace;
  private final Clock clock;
  private final SecureRandom random;

  public CapabilityService(CapabilityGrantStore store, TraceSink trace, Clock clock) {
    this(store, trace, clock, new SecureRandom());
  }

  CapabilityService(CapabilityGrantStore store, TraceSink trace, Clock clock, SecureRandom random) {
    this.store = Objects.requireNonNull(store);
    this.trace = Objects.requireNonNull(trace);
    this.clock = Objects.requireNonNull(clock);
    this.random = Objects.requireNonNull(random);
  }

  public IssuedCapability issue(CapabilityRequest request) {
    var now = clock.instant();
    if (!request.expiresAt().isAfter(now)) {
      throw new IllegalArgumentException("capability expiry must be in the future");
    }
    var bytes = new byte[32];
    random.nextBytes(bytes);
    var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    var digest = digest(token);
    var grant =
        new CapabilityGrant(
            "cap-" + digest.substring(0, 24),
            digest,
            request.subject(),
            request.sessionId(),
            request.runId(),
            request.tool(),
            request.workspace().toString(),
            request.actionDigest(),
            request.planDigest(),
            request.expiresAt(),
            request.maximumUses(),
            0,
            false,
            now);
    store.save(grant);
    event(grant, "capability.issued", Map.of("maximumUses", Integer.toString(grant.maximumUses())));
    return new IssuedCapability(grant.view(), token);
  }

  public Optional<CapabilityView> consume(String token, CapabilityUse use) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    var consumed = store.consume(digest(token), use, clock.instant());
    consumed.ifPresent(
        grant ->
            event(grant, "capability.consumed", Map.of("uses", Integer.toString(grant.uses()))));
    return consumed.map(CapabilityGrant::view);
  }

  public Optional<CapabilityView> revoke(String id) {
    var revoked = store.revoke(id, clock.instant());
    revoked.ifPresent(grant -> event(grant, "capability.revoked", Map.of()));
    return revoked.map(CapabilityGrant::view);
  }

  public Optional<CapabilityView> find(String id) {
    return store.find(id).map(CapabilityGrant::view);
  }

  public List<CapabilityView> list() {
    return store.list().stream().map(CapabilityGrant::view).toList();
  }

  public int purgeExpired() {
    return store.purgeExpired(clock.instant());
  }

  private void event(CapabilityGrant grant, String type, Map<String, String> extra) {
    var attributes = new java.util.LinkedHashMap<String, String>();
    attributes.put("capabilityId", grant.id());
    attributes.put("subject", grant.subject());
    attributes.put("sessionId", grant.sessionId());
    attributes.put("tool", grant.tool());
    attributes.putAll(extra);
    trace.record(grant.runId(), type, clock.instant(), attributes);
  }

  private static String digest(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}

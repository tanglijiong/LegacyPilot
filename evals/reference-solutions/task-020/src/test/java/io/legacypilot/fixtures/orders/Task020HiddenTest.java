package io.legacypilot.fixtures.orders;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class Task020HiddenTest {
  @Test
  void verifiesHmacWithoutAcceptingTamperedOrMalformedSignatures() throws Exception {
    var payload = "order=o1".getBytes(StandardCharsets.UTF_8);
    var secret = "fixture-secret".getBytes(StandardCharsets.UTF_8);
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret, "HmacSHA256"));
    var valid = HexFormat.of().formatHex(mac.doFinal(payload));
    var verifier = new OrderWebhookVerifier();

    assertTrue(verifier.verify(payload, valid, secret));
    assertFalse(verifier.verify("order=o2".getBytes(StandardCharsets.UTF_8), valid, secret));
    assertFalse(verifier.verify(payload, "not-hex", secret));
    assertFalse(verifier.verify(null, valid, secret));
  }
}

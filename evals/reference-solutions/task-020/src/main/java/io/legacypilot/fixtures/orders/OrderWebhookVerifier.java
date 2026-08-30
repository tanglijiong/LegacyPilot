package io.legacypilot.fixtures.orders;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class OrderWebhookVerifier {
  public boolean verify(byte[] payload, String providedSignature, byte[] secret) {
    if (payload == null || providedSignature == null || secret == null || secret.length == 0) {
      return false;
    }
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      var expected = mac.doFinal(payload);
      var provided = HexFormat.of().parseHex(providedSignature);
      return MessageDigest.isEqual(expected, provided);
    } catch (GeneralSecurityException | IllegalArgumentException exception) {
      return false;
    }
  }
}

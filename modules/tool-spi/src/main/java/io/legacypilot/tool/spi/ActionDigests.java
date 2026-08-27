package io.legacypilot.tool.spi;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ActionDigests {

  private ActionDigests() {}

  public static String create(String toolName, JsonNode input) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      digest.update(toolName.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(input.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}

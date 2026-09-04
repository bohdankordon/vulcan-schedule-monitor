package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret;

import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public final class VulcanMasterKey {

  private static final int KEY_BYTES = 32;
  private final SecretKey key;

  private VulcanMasterKey(byte[] bytes) {
    key = new SecretKeySpec(bytes.clone(), "AES");
  }

  public static VulcanMasterKey fromBase64(String encoded) {
    final byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(encoded == null ? "" : encoded);
    } catch (IllegalArgumentException exception) {
      throw invalid();
    }
    if (decoded.length != KEY_BYTES) {
      throw invalid();
    }
    try {
      return new VulcanMasterKey(decoded);
    } finally {
      Arrays.fill(decoded, (byte) 0);
    }
  }

  SecretKey key() {
    return key;
  }

  @Override
  public String toString() {
    return "VulcanMasterKey[value=[redacted]]";
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("VULCAN master key must be Base64 encoded configuration");
  }
}

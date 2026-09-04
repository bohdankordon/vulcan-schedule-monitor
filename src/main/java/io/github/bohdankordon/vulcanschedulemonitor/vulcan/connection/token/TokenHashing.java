package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class TokenHashing {

  private TokenHashing() {}

  public static byte[] sha256(RawConnectToken token) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(token.value().getBytes(StandardCharsets.US_ASCII));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}

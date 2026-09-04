package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret;

public final class SecretDecryptionException extends RuntimeException {

  public SecretDecryptionException() {
    super("Stored VULCAN secret could not be decrypted");
  }
}

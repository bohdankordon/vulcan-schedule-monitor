package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret;

public final class EncryptedPayload {

  private final int keyVersion;
  private final byte[] nonce;
  private final byte[] ciphertext;

  public EncryptedPayload(int keyVersion, byte[] nonce, byte[] ciphertext) {
    this.keyVersion = keyVersion;
    this.nonce = nonce.clone();
    this.ciphertext = ciphertext.clone();
  }

  public int keyVersion() {
    return keyVersion;
  }

  public byte[] nonce() {
    return nonce.clone();
  }

  public byte[] ciphertext() {
    return ciphertext.clone();
  }

  @Override
  public String toString() {
    return "EncryptedPayload[value=[redacted]]";
  }
}

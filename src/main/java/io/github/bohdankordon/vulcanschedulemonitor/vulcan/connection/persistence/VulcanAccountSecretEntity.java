package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "vulcan_account_secret")
class VulcanAccountSecretEntity {

  @Id
  @Column(name = "account_id")
  private Long accountId;

  @Column(name = "key_version", nullable = false)
  private int keyVersion;

  @Column(name = "session_nonce", nullable = false)
  private byte[] sessionNonce;

  @Column(name = "session_ciphertext", nullable = false)
  private byte[] sessionCiphertext;

  @Column(name = "credential_nonce")
  private byte[] credentialNonce;

  @Column(name = "credential_ciphertext")
  private byte[] credentialCiphertext;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected VulcanAccountSecretEntity() {}

  VulcanAccountSecretEntity(long accountId) {
    this.accountId = accountId;
  }

  void replace(
      int keyVersion,
      byte[] sessionNonce,
      byte[] sessionCiphertext,
      byte[] credentialNonce,
      byte[] credentialCiphertext,
      Instant now) {
    this.keyVersion = keyVersion;
    this.sessionNonce = sessionNonce.clone();
    this.sessionCiphertext = sessionCiphertext.clone();
    this.credentialNonce = credentialNonce == null ? null : credentialNonce.clone();
    this.credentialCiphertext = credentialCiphertext == null ? null : credentialCiphertext.clone();
    this.updatedAt = now;
  }

  int keyVersion() {
    return keyVersion;
  }

  byte[] sessionNonce() {
    return sessionNonce.clone();
  }

  byte[] sessionCiphertext() {
    return sessionCiphertext.clone();
  }

  byte[] credentialNonce() {
    return credentialNonce == null ? null : credentialNonce.clone();
  }

  byte[] credentialCiphertext() {
    return credentialCiphertext == null ? null : credentialCiphertext.clone();
  }

  @Override
  public String toString() {
    return "VulcanAccountSecretEntity[value=[redacted]]";
  }
}
